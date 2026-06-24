package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.friends.InviteSlot
import com.zerovpn.app.friends.InviteSlotState
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.theme.*
import com.zerovpn.app.vpn.ExitProvider

@Composable
fun FriendsScreen(
    onCreateOracleExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
) {
    val exits by viewModel.configuredExits.collectAsState()
    val inviteSlots by viewModel.inviteSlots.collectAsState()
    val selectedExitId by viewModel.selectedExitId.collectAsState()
    val ownerExits = exits.filter { it.provider == ExitProvider.OCI }
    val selectedOwnerExit = ownerExits.firstOrNull { it.id == selectedExitId } ?: ownerExits.firstOrNull()
    val slotsForOwner = selectedOwnerExit
        ?.let { exit -> inviteSlots.filter { it.ownerExitId == exit.id }.sortedBy { it.slotIndex } }
        .orEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        Text(
            text = "FRIENDS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        when {
            selectedOwnerExit == null -> {
                FriendsEmptyState(
                    body = "No shareable exit yet.",
                    supportingCopy = "Create an Oracle Free Exit first, then you can share it with trusted friends.",
                    actionText = "Create Oracle Free Exit",
                    onAction = onCreateOracleExit,
                    actionEnabled = true,
                )
            }
            slotsForOwner.isEmpty() -> {
                FriendsEmptyState(
                    body = "Sharing is not set up for this exit yet.",
                    supportingCopy = "Future Oracle exits will create three share slots automatically.",
                    actionText = "Coming next",
                    onAction = {},
                    actionEnabled = false,
                )
            }
            else -> {
                Text(
                    text = selectedOwnerExit.name,
                    fontSize = 13.sp,
                    color = TextDim,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                slotsForOwner.take(3).forEach { slot ->
                    InviteSlotCard(slot = slot)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
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
private fun InviteSlotCard(slot: InviteSlot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Text(
            text = if (slot.slotIndex > 0) "Friend slot ${slot.slotIndex}" else "Friend slot",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = slot.displayName ?: "Unused invite",
            fontSize = 13.sp,
            color = TextDim,
        )
        Spacer(modifier = Modifier.height(2.dp))
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
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Coming next", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextDim)
        }
    }
}

private fun InviteSlotState.displayText(): String = when (this) {
    InviteSlotState.UNUSED -> "Unused"
    InviteSlotState.PENDING_CLAIM -> "Pending"
    InviteSlotState.CLAIMED -> "Claimed"
    InviteSlotState.REVOKED -> "Revoked"
}
