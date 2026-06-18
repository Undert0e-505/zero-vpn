package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerovpn.app.ui.theme.*

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
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
                text = "Version 0.1.0-dev",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = TextDim,
            )
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
                text = "ZeroVPN is a community-driven VPN client built on WireGuard. It enables private, encrypted connections through volunteer-operated exit nodes.",
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Threat model: github.com/Undert0e-505/zero-vpn/blob/main/docs/THREAT-MODEL.md",
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