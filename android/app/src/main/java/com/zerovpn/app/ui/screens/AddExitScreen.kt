package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerovpn.app.ui.components.OptionButton
import com.zerovpn.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AddExitScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

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
            icon = Icons.Default.Add,
            label = "Import Config",
            onClick = {
                scope.launch {
                    snackbarHostState.showSnackbar("Coming soon")
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.QrCodeScanner,
            label = "Scan QR Invite",
            onClick = {
                scope.launch {
                    snackbarHostState.showSnackbar("Coming soon")
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.Wifi,
            label = "Create Private Node",
            onClick = {
                scope.launch {
                    snackbarHostState.showSnackbar("Coming soon")
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        OptionButton(
            icon = Icons.Default.VolunteerActivism,
            label = "Volunteer Network",
            onClick = {
                scope.launch {
                    snackbarHostState.showSnackbar("Coming soon")
                }
            },
        )
    }
}