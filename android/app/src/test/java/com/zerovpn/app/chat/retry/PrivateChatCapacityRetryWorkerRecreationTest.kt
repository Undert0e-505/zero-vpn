package com.zerovpn.app.chat.retry

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.google.common.util.concurrent.ListenableFuture
import com.zerovpn.app.ZeroVpnApp
import com.zerovpn.app.oci.OciRequestSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(application = ZeroVpnApp::class, sdk = [35])
class PrivateChatCapacityRetryWorkerRecreationTest {
    @Test fun mergedManifestKeepsTheDefaultWorkManagerInitializer() {
        val app = RuntimeEnvironment.getApplication()
        val provider = app.packageManager.getProviderInfo(
            ComponentName(app.packageName, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA,
        )

        assertEquals(
            "androidx.startup",
            provider.metaData.getString("androidx.work.WorkManagerInitializer"),
        )
    }

    @Test fun defaultWorkerFactoryRecreatesWorkerFromApplicationContextOnly() {
        val app = RuntimeEnvironment.getApplication()
        val factory = Configuration.Builder().build().workerFactory
        val worker = factory.createWorkerWithDefaultFallback(
            app,
            PrivateChatCapacityRetryWorker::class.java.name,
            workerParameters("session:recreated"),
        )

        assertTrue(worker is PrivateChatCapacityRetryWorker)
        val capacityWorker = worker as PrivateChatCapacityRetryWorker
        assertEquals(app, capacityWorker.applicationContext)
        val dependencies = capacityWorker.loadDependenciesFromApplicationContext()
        assertNotNull(dependencies.repository)
        assertNotNull(dependencies.vault)
        assertNotNull(dependencies.provisioningLease)
        assertFalse(dependencies.provisioningLease.isHeldByLiveProcess())
    }

    @Test fun workInfoQueryReadsRealEnqueuedStateFromWorkManager() {
        val app = RuntimeEnvironment.getApplication()
        val workManager = runCatching { WorkManager.getInstance(app) }.getOrElse {
            WorkManager.initialize(app, Configuration.Builder().build())
            WorkManager.getInstance(app)
        }
        val now = Instant.now()
        val session = CapacityRetrySession.newSession(
            candidateId = "candidate:" + UUID.randomUUID(),
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..example",
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val scheduler = CapacityRetryWorkScheduler(
            context = app,
            workManager = workManager,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        var observedState: CapacityRetrySchedulerState? = null
        val liveData = CapacityRetryWorkMonitor(app, workManager)
            .liveData(session.uniqueWorkName)
        val observer = Observer<List<androidx.work.WorkInfo>> { workInfos ->
            observedState = capacityRetrySchedulerStatus(workInfos.orEmpty()).state
        }
        liveData.observeForever(observer)

        assertTrue(scheduler.enqueue(session))
        val workInfos = workManager
            .getWorkInfosForUniqueWork(session.uniqueWorkName)
            .get(10, TimeUnit.SECONDS)
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            if (observedState == CapacityRetrySchedulerState.ENQUEUED) return@repeat
            Thread.sleep(10)
        }

        assertEquals(
            CapacityRetrySchedulerState.ENQUEUED,
            capacityRetrySchedulerStatus(workInfos).state,
        )
        assertEquals(CapacityRetrySchedulerState.ENQUEUED, observedState)
        liveData.removeObserver(observer)
        workManager.cancelUniqueWork(session.uniqueWorkName).result.get(10, TimeUnit.SECONDS)
    }

    @Test fun unexpectedLaunchExceptionPausesCancelsAndPreservesReconciliationInputs() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(CapacityRetryRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val workManager = runCatching { WorkManager.getInstance(app) }.getOrElse {
            WorkManager.initialize(app, Configuration.Builder().build())
            WorkManager.getInstance(app)
        }
        val now = Instant.now()
        val firstAttemptFinished = now.minusSeconds(16 * 60L)
        val prefs = FakeSharedPreferences()
        val repository = CapacityRetryRepository(prefs, Clock.fixed(now, ZoneOffset.UTC))
        val session = repository.createSession(
            candidateId = "candidate:ambiguous-worker",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..tenancy",
            userOcid = "ocid1.user.oc1..user",
            tenancyOcid = "ocid1.tenancy.oc1..tenancy",
            fingerprint = "aa:bb:cc",
            selectedRegion = "uk-london-1",
            subnetId = "ocid1.subnet.oc1..subnet",
            sshPublicKey = "ssh-rsa AAAA zerovpn-android",
            initialLaunchAttemptFinishedAtUtc = firstAttemptFinished.toString(),
            initialNextEligibleAttemptAtUtc = firstAttemptFinished.plusSeconds(15 * 60L).toString(),
            pendingMemoryGb = 6,
        )
        val secretStore = TrackingRetrySecretStore()
        val vault = RetryCredentialVault(secretStore)
        val signingKey = OciRequestSigner.generateKeyPair()
        val realFingerprint = com.zerovpn.app.oci.OciCredentialIdentity.fingerprintOf(
            com.zerovpn.app.oci.OciCredentialIdentity.publicKeyFrom(signingKey.private)
        )
        assertTrue(
            vault.storeApiKeyCredentials(
                session.sessionId,
                DurableApiKeyCredentials(
                    tenancyOcid = "ocid1.tenancy.oc1..tenancy",
                    userOcid = "ocid1.user.oc1..user",
                    fingerprint = realFingerprint,
                    privateKeyPem = RetryCredentialVault.privateKeyToPkcs8Pem(signingKey.private),
                    region = "uk-london-1",
                    publicKeySha256 = null,
                ),
            ),
        )
        val credentialsBefore = vault.loadApiKeyCredentials(session.sessionId)
        val diagnosticLog = CapacityRetryDiagnosticLog(prefs, Clock.fixed(now, ZoneOffset.UTC))
        val launcher = object : BackgroundLaunchExecutor {
            var receivedRetryToken: String? = null

            override suspend fun launchA1Instance(
                credentials: BackgroundLaunchCredentials,
                params: BackgroundLaunchParams,
                pendingMemoryGb: Int,
                retryToken: String,
                sessionId: String,
            ): BackgroundLaunchResult {
                receivedRetryToken = retryToken
                throw IllegalStateException(
                    "unknown launcher failure Authorization: Bearer should-never-be-stored",
                )
            }
        }
        val scheduler = CapacityRetryWorkScheduler(
            context = app,
            workManager = workManager,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        assertTrue(scheduler.enqueue(session))
        val worker = PrivateChatCapacityRetryWorker(
            app,
            workerParameters(session.sessionId),
            PrivateChatCapacityRetryWorkerDependencies(
                repository = repository,
                vault = vault,
                provisioningLease = ProvisioningOperationLease(prefs, processInstanceId = "worker-test"),
                diagnosticLog = diagnosticLog,
                notifier = CapacityRetryNotifier(app),
                launcher = launcher,
                workScheduler = scheduler,
            ),
        )

        worker.doWork()

        val blocked = requireNotNull(repository.session(session.sessionId))
        assertEquals(CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED, blocked.state)
        assertEquals("AMBIGUOUS_EXCEPTION_RECONCILIATION_REQUIRED", blocked.lastResult)
        assertEquals(session.deadlineUtc, blocked.deadlineUtc)
        assertEquals(session.pendingMemoryGb, blocked.pendingMemoryGb)
        assertEquals(launcher.receivedRetryToken, blocked.preferredRetryToken)
        assertEquals(0, blocked.retryCycleCount)
        assertEquals(0, blocked.launchRequestCount6Gb)
        assertNull(blocked.nextEligibleAttemptAtUtc)
        assertEquals(credentialsBefore, vault.loadApiKeyCredentials(session.sessionId))
        assertFalse(secretStore.removedKeys.any { it.contains(session.sessionId) })

        val detailed = diagnosticLog.entries(session.sessionId)
            .lastOrNull { it.failureDiagnostics != null }
            ?.failureDiagnostics
        assertNotNull(detailed)
        assertFalse(detailed!!.fullDiagnosticBlock().contains("should-never-be-stored"))

        var schedulerState = capacityRetrySchedulerStatus(
            workManager.getWorkInfosForUniqueWork(session.uniqueWorkName).get(10, TimeUnit.SECONDS),
        ).state
        repeat(50) {
            if (schedulerState != CapacityRetrySchedulerState.CANCELLED) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
                schedulerState = capacityRetrySchedulerStatus(
                    workManager.getWorkInfosForUniqueWork(session.uniqueWorkName).get(10, TimeUnit.SECONDS),
                ).state
            }
        }
        assertEquals(CapacityRetrySchedulerState.CANCELLED, schedulerState)
    }

    private fun workerParameters(sessionId: String): WorkerParameters {
        val directExecutor = Executor(Runnable::run)
        val serialExecutor = object : SerialExecutor {
            override fun execute(command: Runnable) = command.run()
            override fun hasPendingTasks(): Boolean = false
        }
        val taskExecutor = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = directExecutor
            override fun executeOnTaskThread(runnable: Runnable) = runnable.run()
            override fun getSerialTaskExecutor(): SerialExecutor = serialExecutor
            override fun getTaskCoroutineDispatcher() = Dispatchers.Unconfined
        }
        return WorkerParameters(
            UUID.randomUUID(),
            Data.Builder()
                .putString(PrivateChatCapacityRetryWorker.KEY_SESSION_ID, sessionId)
                .build(),
            emptySet(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            directExecutor,
            EmptyCoroutineContext,
            taskExecutor,
            Configuration.Builder().build().workerFactory,
            ProgressUpdater { _, _, _ -> completedVoidFuture() },
            ForegroundUpdater { _, _, _ -> completedVoidFuture() },
        )
    }

    private fun completedVoidFuture(): ListenableFuture<Void> =
        SettableFuture.create<Void>().also { it.set(null) }

    private class TrackingRetrySecretStore : RetrySecretStore {
        private val values = mutableMapOf<String, String>()
        val removedKeys = mutableListOf<String>()

        override fun putSecret(key: String, value: String) {
            values[key] = value
        }

        override fun getSecret(key: String): String? = values[key]

        override fun removeSecret(key: String) {
            removedKeys += key
            values.remove(key)
        }

        override fun putSecrets(secrets: Map<String, String>): Boolean {
            values.putAll(secrets)
            return true
        }
    }
}
