package com.zerovpn.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.friends.InviteSlot
import com.zerovpn.app.friends.InviteSlotState
import com.zerovpn.app.friends.QrCodeGenerator
import com.zerovpn.app.ui.provisioning.InviteClaimCheckResult
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.theme.*
import com.zerovpn.app.vpn.ConfiguredExit
import com.zerovpn.app.vpn.ExitProvider
import java.text.DateFormat
import java.util.Date

@Composable
fun FriendsScreen(
    onCreateOracleExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
) {
    val context = LocalContext.current
    val exits by viewModel.configuredExits.collectAsState()
    val inviteSlots by viewModel.inviteSlots.collectAsState()
    val ownerExits = exits.filter { it.provider == ExitProvider.OCI }
    val slotsByOwnerId = inviteSlots.groupBy { it.ownerExitId }
    val scrollState = rememberScrollState()
    var shareTarget by remember { mutableStateOf<InviteSlot?>(null) }
    var renameTarget by remember { mutableStateOf<InviteSlot?>(null) }
    var qrDialog by remember { mutableStateOf<QrDialogState?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var checkingSlotId by remember { mutableStateOf<String?>(null) }

    fun showQrForSlot(slot: InviteSlot, markPending: Boolean, name: String? = null) {
        if (slot.state == InviteSlotState.CLAIMED) {
            errorMessage = "This invite has already been claimed. The QR is no longer available."
            return
        }
        val config = viewModel.getInviteSlotClientConfig(slot.slotId)?.takeIf { it.isNotBlank() }
        if (config == null) {
            errorMessage = "This invite slot is missing its share config. Recreate the exit or wait for a future recovery flow."
            return
        }
        val bitmap = runCatching { QrCodeGenerator.generate(config) }
            .getOrElse {
                errorMessage = "ZeroVPN could not generate a QR code for this invite."
                return
            }
        val displayName = name?.trim()?.takeIf { it.isNotBlank() }
            ?: slot.displayName?.takeIf { it.isNotBlank() }
            ?: "Friend slot ${slot.slotIndex}"
        if (name != null) {
            viewModel.renameInviteSlot(slot.slotId, displayName)
        }
        if (markPending) {
            viewModel.markInviteSlotPending(slot.slotId, System.currentTimeMillis())
        }
        qrDialog = QrDialogState(displayName = displayName, bitmap = bitmap)
    }

    fun checkClaim(slot: InviteSlot) {
        if (checkingSlotId != null) return
        checkingSlotId = slot.slotId
        viewModel.checkInviteSlotClaim(context, slot.slotId) { result ->
            checkingSlotId = null
            when (result) {
                InviteClaimCheckResult.NotClaimed -> {
                    infoMessage = "Not claimed yet. This invite has not connected."
                }
                InviteClaimCheckResult.Claimed -> {
                    infoMessage = "Invite claimed. The QR has now been hidden."
                }
                is InviteClaimCheckResult.Error -> {
                    errorMessage = result.message
                }
            }
        }
    }

    shareTarget?.let { slot ->
        NameInviteDialog(
            slot = slot,
            onDismiss = { shareTarget = null },
            onConfirm = { name ->
                shareTarget = null
                showQrForSlot(slot = slot, markPending = true, name = name)
            },
        )
    }

    renameTarget?.let { slot ->
        RenameInviteDialog(
            slot = slot,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                viewModel.renameInviteSlot(slot.slotId, name.ifBlank { null })
            },
        )
    }

    qrDialog?.let { state ->
        ShareQrDialog(
            state = state,
            onDismiss = { qrDialog = null },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = { Text("Invite unavailable", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(message, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK", color = Accent)
                }
            },
        )
    }

    infoMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = { Text("Invite claim", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(message, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { infoMessage = null }) {
                    Text("OK", color = Accent)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        Text(
            text = "FRIENDS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (ownerExits.isEmpty()) {
            FriendsEmptyState(
                body = "No shareable exit yet.",
                supportingCopy = "Create an Oracle Free Exit first, then you can share it with trusted friends.",
                actionText = "Create Oracle Free Exit",
                onAction = onCreateOracleExit,
                actionEnabled = true,
            )
            return@Column
        }

        ownerExits.forEach { ownerExit ->
            val slotsForOwner = slotsByOwnerId[ownerExit.id].orEmpty().sortedBy { it.slotIndex }
            OwnerExitSectionTitle(ownerExit)
            if (slotsForOwner.isEmpty()) {
                FriendsEmptyState(
                    body = "Sharing is not set up for this exit yet.",
                    supportingCopy = "New Oracle exits create three friend invite slots automatically.",
                    actionText = "Coming next",
                    onAction = {},
                    actionEnabled = false,
                )
            } else {
                slotsForOwner.take(3).forEach { slot ->
                    InviteSlotCard(
                        slot = slot,
                        onShare = { shareTarget = slot },
                        onShowQrAgain = { showQrForSlot(slot = slot, markPending = false) },
                        onCheckClaim = { checkClaim(slot) },
                        onRename = { renameTarget = slot },
                        isCheckingClaim = checkingSlotId == slot.slotId,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun OwnerExitSectionTitle(ownerExit: ConfiguredExit) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        Text(
            text = ownerExit.name.ifBlank { "Oracle Exit" },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Text(
            text = "${ownerExit.region} - ${ownerExit.publicIp}:${ownerExit.wireGuardPort}",
            fontSize = 12.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun FriendsEmptyState(
    body: String,
    supportingCopy: String,
    actionText: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = body,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = supportingCopy,
                fontSize = 13.sp,
                color = TextDim,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                    disabledContainerColor = Border,
                    disabledContentColor = TextDim,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = actionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun InviteSlotCard(
    slot: InviteSlot,
    onShare: () -> Unit,
    onShowQrAgain: () -> Unit,
    onCheckClaim: () -> Unit,
    onRename: () -> Unit,
    isCheckingClaim: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Text(
            text = slot.displayName?.takeIf { it.isNotBlank() }
                ?: if (slot.slotIndex > 0) "Friend slot ${slot.slotIndex}" else "Friend slot",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "State: ${slot.state.displayText()}",
            fontSize = 13.sp,
            color = TextDim,
        )
        slot.tunnelIp?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Tunnel IP: $it",
                fontSize = 12.sp,
                color = TextDim,
            )
        }
        slot.peerPublicKey?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Peer: ${it.prefixForInviteCard()}",
                fontSize = 12.sp,
                color = TextDim,
            )
        }
        slot.firstHandshakeAt?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text("First connected: ${it.formatInviteTime()}", fontSize = 12.sp, color = TextDim)
        }
        slot.lastHandshakeAt?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text("Last seen: ${it.formatInviteTime()}", fontSize = 12.sp, color = TextDim)
        }
        if (slot.state == InviteSlotState.CLAIMED) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This invite has been claimed. The QR will not be shown again.",
                fontSize = 13.sp,
                color = TextDim,
                lineHeight = 18.sp,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        when (slot.state) {
            InviteSlotState.UNUSED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Share", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onRename,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Rename", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
                    }
                }
            }
            InviteSlotState.PENDING_CLAIM -> {
                Text("Waiting for first connection", fontSize = 12.sp, color = TextDim)
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onCheckClaim,
                            enabled = !isCheckingClaim,
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                if (isCheckingClaim) "Checking..." else "Check claim",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        OutlinedButton(
                            onClick = onShowQrAgain,
                            enabled = !isCheckingClaim,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Show QR", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
                        }
                    }
                    OutlinedButton(
                        onClick = onRename,
                        enabled = !isCheckingClaim,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Rename", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
                    }
                }
            }
            InviteSlotState.CLAIMED -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onRename,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Rename", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
                    }
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Revoke/reset coming next", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextDim)
                    }
                }
            }
            InviteSlotState.REVOKED -> {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Reset coming next", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextDim)
                }
            }
        }
    }
}

@Composable
private fun NameInviteDialog(
    slot: InviteSlot,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(slot.slotId) { mutableStateOf(slot.displayName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = { Text("Who is this for?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Friend phone") },
                    singleLine = true,
                )
                Text(
                    text = "Leave this blank to use Friend slot ${slot.slotIndex}.",
                    fontSize = 12.sp,
                    color = TextDim,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "Friend slot ${slot.slotIndex}" }) }) {
                Text("Show QR", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

@Composable
private fun RenameInviteDialog(
    slot: InviteSlot,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(slot.slotId) { mutableStateOf(slot.displayName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = { Text("Rename invite", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Friend slot ${slot.slotIndex}") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text("Save", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

@Composable
private fun ShareQrDialog(
    state: QrDialogState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = { Text("Share once", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Show this QR to someone you trust. Anyone who scans it can use your exit until you revoke it. Their traffic will use your Oracle VM's public IP.",
                    fontSize = 13.sp,
                    color = TextPrimary,
                    lineHeight = 18.sp,
                )
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = "WireGuard invite QR for ${state.displayName}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(260.dp)
                        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
                Text(
                    text = "ZeroVPN will hide it after first successful connection once claim checking is enabled.",
                    fontSize = 12.sp,
                    color = TextDim,
                    lineHeight = 17.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Accent)
            }
        },
    )
}

private data class QrDialogState(
    val displayName: String,
    val bitmap: Bitmap,
)

private fun InviteSlotState.displayText(): String = when (this) {
    InviteSlotState.UNUSED -> "Unused"
    InviteSlotState.PENDING_CLAIM -> "Pending"
    InviteSlotState.CLAIMED -> "Claimed"
    InviteSlotState.REVOKED -> "Revoked"
}

private fun Long.formatInviteTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))

private fun String.prefixForInviteCard(): String =
    take(8).takeIf { it.isNotBlank() } ?: "N/A"
