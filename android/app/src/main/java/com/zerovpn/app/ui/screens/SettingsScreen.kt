package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerovpn.app.BuildConfig
import com.zerovpn.app.ui.theme.*

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    isDevMode: Boolean = false,
    onDevModeChange: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        Text(
            text = "ABOUT",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // App info card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .padding(16.dp),
        ) {
            Text(
                text = "ZeroVPN",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = TextDim,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Developer mode toggle
        Text(
            text = "DEVELOPER",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Developer Mode",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isDevMode)
                            "Shows detailed telemetry: wire tap, signing strings, HTTP headers"
                        else
                            "Streamlined progress view — no technical details",
                        fontSize = 12.sp,
                        color = TextDim,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isDevMode,
                    onCheckedChange = onDevModeChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextDim,
                        uncheckedTrackColor = Surface,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "ABOUT ZEROVPN",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .padding(16.dp),
        ) {
            Text(
                text = "ZeroVPN is an experimental open-source Android app for creating personal VPN exits. It can provision an Oracle Free Exit, import shared WireGuard exits by QR, share trusted friend invites, and use an experimental Volunteer Exit. ZeroVPN is not a VPN provider and does not operate central exit nodes.",
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Threat model: https://github.com/Undert0e-505/zero-vpn/blob/main/docs/THREAT_MODEL.md",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = TextDim,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Source: github.com/Undert0e-505/zero-vpn",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = TextDim,
            )
        }
    }
}
