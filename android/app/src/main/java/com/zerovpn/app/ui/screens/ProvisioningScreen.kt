package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.ui.provisioning.Phase
import com.zerovpn.app.ui.provisioning.ProvisioningEvent
import com.zerovpn.app.ui.provisioning.ProvisioningState
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.provisioning.Status
import com.zerovpn.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Status colors for terminal output
private val SuccessGreen = Color(0xFF4CAF50)
private val WarningYellow = Color(0xFFFFC107)

@Composable
fun ProvisioningScreen(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val events by viewModel.events.collectAsState()
    val currentPhase by viewModel.currentPhase.collectAsState()
    val publicIp by viewModel.publicIp.collectAsState()
    val wireGuardPort by viewModel.wireGuardPort.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "ORACLE PROVISIONING",
                style = SectionTitleStyle,
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        when (val s = state) {
            is ProvisioningState.Idle -> {
                PreStartContent(
                    onStart = { viewModel.startProvisioning() },
                    onCancel = { viewModel.cancel(); onBack() },
                )
            }

            is ProvisioningState.PreStart -> {
                PreStartContent(
                    onStart = { viewModel.startProvisioning() },
                    onCancel = { viewModel.cancel(); onBack() },
                )
            }

            is ProvisioningState.Running -> {
                ProgressContent(
                    events = events,
                    currentPhase = currentPhase,
                )
            }

            is ProvisioningState.Success -> {
                SuccessContent(
                    publicIp = s.publicIp,
                    wireGuardPort = s.wireGuardPort,
                    onConnect = {
                        android.widget.Toast.makeText(
                            context,
                            "WireGuard connection coming soon",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onDestroy = { viewModel.destroyNode() },
                )
            }

            is ProvisioningState.Failure -> {
                FailureContent(
                    failedPhase = s.failedPhase,
                    lastSuccessPhase = s.lastSuccessPhase,
                    events = events,
                    onRetry = { viewModel.retry() },
                    onCleanup = { viewModel.cleanup() },
                )
            }
        }
    }
}

// -- Pre-start -------------------------------------------------

@Composable
private fun PreStartContent(
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Before you start",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )

        Text(
            text = "Creating your Oracle exit usually takes 4-6 minutes. " +
                "Keep ZeroVPN open while it creates the cloud network, " +
                "VM, WireGuard server, and local phone config.",
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = TextDim,
            lineHeight = 22.sp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextDim,
                ),
            ) {
                Text("Cancel", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Text("Start", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -- Progress (terminal-style) --------------------------------

@Composable
private fun ProgressContent(
    events: List<ProvisioningEvent>,
    currentPhase: Phase?,
) {
    val scrollState = rememberScrollState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Phase progress indicator
    if (currentPhase != null && currentPhase != Phase.DONE) {
        Text(
            text = "Phase ${currentPhase.number}/6: ${currentPhase.label}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Accent,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    // Terminal-style console
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .verticalScroll(scrollState),
    ) {
        LaunchedEffect(events.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        if (events.isEmpty()) {
            Text(
                text = "Initializing...",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = TextDim,
            )
        }

        events.forEach { event ->
            val timeStr = timeFormat.format(Date(event.timestamp))
            val phaseStr = "[${event.phase.number}/${Phase.entries.size}]"
            val statusColor = when (event.status) {
                Status.RUNNING -> Accent
                Status.SUCCESS -> SuccessGreen
                Status.WARNING -> WarningYellow
                Status.ERROR -> Danger
            }
            val statusIcon = when (event.status) {
                Status.RUNNING -> ">"
                Status.SUCCESS -> "+"
                Status.WARNING -> "!"
                Status.ERROR -> "x"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    text = "$timeStr $phaseStr",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDim,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusIcon,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = event.message,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
            }
        }
    }
}

// -- Success --------------------------------------------------

@Composable
private fun SuccessContent(
    publicIp: String,
    wireGuardPort: Int,
    onConnect: () -> Unit,
    onDestroy: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "Exit created successfully",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoRow("Public IP", publicIp)
            InfoRow("WireGuard Port", "$wireGuardPort/udp")
            InfoRow("Region", "uk-london-1")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onConnect,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect Now", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onDestroy,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Danger,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Destroy Node", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// -- Failure --------------------------------------------------

@Composable
private fun FailureContent(
    failedPhase: Phase,
    lastSuccessPhase: Phase?,
    events: List<ProvisioningEvent>,
    onRetry: () -> Unit,
    onCleanup: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Danger,
                modifier = Modifier.size(32.dp),
            )
            Column {
                Text(
                    text = "Provisioning failed",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Danger,
                )
                Text(
                    text = "Failed at: ${failedPhase.label}",
                    fontSize = 13.sp,
                    color = TextDim,
                )
                if (lastSuccessPhase != null) {
                    Text(
                        text = "Last success: ${lastSuccessPhase.label}",
                        fontSize = 13.sp,
                        color = TextDim,
                    )
                }
            }
        }

        // Event log (scrollable, terminal style)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .verticalScroll(scrollState),
        ) {
            events.forEach { event ->
                val timeStr = timeFormat.format(Date(event.timestamp))
                val phaseStr = "[${event.phase.number}/${Phase.entries.size}]"
                val statusColor = when (event.status) {
                    Status.RUNNING -> Accent
                    Status.SUCCESS -> SuccessGreen
                    Status.WARNING -> WarningYellow
                    Status.ERROR -> Danger
                }
                val statusIcon = when (event.status) {
                    Status.RUNNING -> ">"
                    Status.SUCCESS -> "+"
                    Status.WARNING -> "!"
                    Status.ERROR -> "x"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = "$timeStr $phaseStr",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextDim,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusIcon,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = statusColor,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = event.message,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = statusColor,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCleanup,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextDim,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cleanup", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -- Helper ---------------------------------------------------

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextDim,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
        )
    }
}