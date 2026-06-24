package com.zerovpn.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.zerovpn.app.friends.ParsedWireGuardInvite
import com.zerovpn.app.friends.WireGuardInviteParser
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.theme.Accent
import com.zerovpn.app.ui.theme.Bg
import com.zerovpn.app.ui.theme.Border
import com.zerovpn.app.ui.theme.SectionTitleStyle
import com.zerovpn.app.ui.theme.Surface
import com.zerovpn.app.ui.theme.TextDim
import com.zerovpn.app.ui.theme.TextPrimary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanInviteScreen(
    onCancel: () -> Unit,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var decodedInvite by remember { mutableStateOf<ParsedWireGuardInvite?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanLocked by remember { mutableStateOf(false) }
    var importName by remember(decodedInvite?.configHash) { mutableStateOf("Shared Exit") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(TAG, "QR scanner camera permission ${if (granted) "granted" else "denied"}")
        permissionGranted = granted
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            Log.d(TAG, "QR scanner camera permission already granted")
        }
    }

    decodedInvite?.let { invite ->
        AlertDialog(
            onDismissRequest = {
                Log.d(TAG, "QR import confirmation cancelled")
                decodedInvite = null
                scanLocked = false
            },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = {
                Text("Import shared exit", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(
                        value = importName,
                        onValueChange = { importName = it },
                        singleLine = true,
                    )
                    Text(
                        text = "Endpoint: ${invite.endpoint}",
                        fontSize = 13.sp,
                        color = TextDim,
                    )
                    Text(
                        text = "This shared exit was created by someone else. It can connect through their WireGuard server, but it cannot manage or destroy that server.",
                        fontSize = 13.sp,
                        color = TextPrimary,
                        lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.hasImportedSharedExit(invite)) {
                        Log.d(TAG, "QR import duplicate detected")
                        errorMessage = "This shared exit already appears to be imported."
                        decodedInvite = null
                        scanLocked = true
                        return@TextButton
                    }
                    Log.d(TAG, "QR import saved")
                    viewModel.importSharedExit(invite, importName)
                    decodedInvite = null
                    onImported()
                }) {
                    Text("Save shared exit", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    Log.d(TAG, "QR import confirmation cancelled")
                    decodedInvite = null
                    scanLocked = false
                }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {
                errorMessage = null
                scanLocked = false
                Log.d(TAG, "QR scanner retry after import error")
            },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = { Text("Could not import", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(message, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    errorMessage = null
                    scanLocked = false
                    Log.d(TAG, "QR scanner retry after import error")
                }) {
                    Text("OK", color = Accent)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        Text(
            text = "SCAN QR INVITE",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (!permissionGranted) {
            PermissionDeniedState(
                onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel,
            )
            return@Column
        }

        Text(
            text = "Point your camera at a ZeroVPN or WireGuard invite QR.",
            fontSize = 13.sp,
            color = TextDim,
            lineHeight = 18.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            QrCameraPreview(
                scanEnabled = !scanLocked && decodedInvite == null,
                onPayload = { payload ->
                    Log.d(TAG, "QR detected; scan guard set")
                    scanLocked = true
                    val parsed = WireGuardInviteParser.parse(payload).getOrElse {
                        Log.d(TAG, "QR parse failed")
                        errorMessage = "This QR does not look like a ZeroVPN or WireGuard invite."
                        return@QrCameraPreview
                    }
                    if (viewModel.hasImportedSharedExit(parsed)) {
                        Log.d(TAG, "QR scan matched an already imported shared exit")
                        errorMessage = "This shared exit already appears to be imported."
                        return@QrCameraPreview
                    }
                    Log.d(TAG, "QR import confirmation shown")
                    decodedInvite = parsed
                    importName = "Shared Exit"
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(260.dp)
                    .border(2.dp, Accent, RoundedCornerShape(8.dp)),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Cancel", fontSize = 13.sp, color = TextPrimary)
        }
    }
}

@Composable
private fun PermissionDeniedState(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Camera access is needed to scan an invite QR. You can allow camera access and try again.",
            fontSize = 14.sp,
            color = TextPrimary,
            lineHeight = 20.sp,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Try again", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Cancel", fontSize = 13.sp, color = TextPrimary)
        }
    }
}

@Composable
private fun QrCameraPreview(
    scanEnabled: Boolean,
    onPayload: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanEnabledState by rememberUpdatedState(scanEnabled)
    val onPayloadState by rememberUpdatedState(onPayload)
    val delivered = remember { AtomicBoolean(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraBound by remember { mutableStateOf(false) }

    LaunchedEffect(scanEnabled) {
        if (scanEnabled) {
            delivered.set(false)
            Log.d(TAG, "QR analyzer started")
        } else {
            delivered.set(true)
            cameraProvider?.unbindAll()
            cameraBound = false
            Log.d(TAG, "QR camera unbound")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            delivered.set(true)
            cameraProvider?.unbindAll()
            cameraBound = false
            executor.shutdown()
            Log.d(TAG, "QR scanner disposed; camera released")
        }
    }

    key(scanEnabled) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    if (!scanEnabledState || delivered.get()) {
                        provider.unbindAll()
                        cameraBound = false
                        Log.d(TAG, "QR camera bind skipped because scan is complete")
                        return@addListener
                    }
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(executor) { image ->
                                if (!scanEnabledState || delivered.get()) {
                                    Log.d(TAG, "QR analyzer frame ignored after scan complete")
                                    image.close()
                                    return@setAnalyzer
                                }
                                try {
                                    val payload = decodeQrPayload(image)
                                    if (payload != null && delivered.compareAndSet(false, true)) {
                                        Log.d(TAG, "QR detected by analyzer")
                                        previewView.post {
                                            provider.unbindAll()
                                            cameraBound = false
                                            Log.d(TAG, "QR camera unbound after successful capture")
                                            onPayloadState(payload)
                                        }
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                        }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer,
                    )
                    cameraBound = true
                    Log.d(TAG, "QR camera bound")
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            update = {
                if (!scanEnabled && cameraBound) {
                    delivered.set(true)
                    cameraProvider?.unbindAll()
                    cameraBound = false
                    Log.d(TAG, "QR camera unbound from update")
                }
            },
        )
    }
}

private fun decodeQrPayload(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val bytes = if (rowStride == width) {
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } else {
        ByteArray(width * height).also { output ->
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                val length = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, length)
                System.arraycopy(row, 0, output, y * width, width)
            }
        }
    }
    val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

private const val TAG = "ZeroVpnQrScanner"
