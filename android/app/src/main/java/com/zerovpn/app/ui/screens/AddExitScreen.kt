package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerovpn.app.ui.components.OptionButton
import com.zerovpn.app.ui.theme.*

@Composable
fun AddExitScreen(
    snackbarHostState: SnackbarHostState,
    onNavigateToProvision: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var disabledMessage by remember { mutableStateOf<String?>(null) }

    disabledMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { disabledMessage = null },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
            title = {
                Text(
                    text = "Coming later",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { disabledMessage = null }) {
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
            text = "ADD EXIT",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        OptionButton(
            icon = Icons.Default.Cloud,
            label = "Create Oracle Free Exit",
            onClick = onNavigateToProvision,
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.VolunteerActivism,
            label = "Volunteer Network",
            enabled = false,
            statusLabel = "Coming Soon",
            onClick = {},
            onDisabledClick = {
                disabledMessage = "Volunteer Network is coming later. Oracle exits are available now."
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.QrCodeScanner,
            label = "Scan QR Invite",
            enabled = false,
            statusLabel = "Coming Soon",
            onClick = {},
            onDisabledClick = {
                disabledMessage = "QR invites are not implemented yet."
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.Add,
            label = "Import Config",
            enabled = false,
            statusLabel = "Coming Soon",
            onClick = {},
            onDisabledClick = {
                disabledMessage = "WireGuard config import is not implemented yet."
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.Wifi,
            label = "Create Private Node",
            enabled = false,
            statusLabel = "Coming Soon",
            onClick = {},
            onDisabledClick = {
                disabledMessage = "Private node setup is not implemented yet."
            },
        )
    }
}
