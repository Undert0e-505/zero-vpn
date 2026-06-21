package com.zerovpn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerovpn.app.ui.theme.*

@Composable
fun StatusCard(
    statusText: String,
    modeLabel: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = false,
    buttonEnabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status indicator circle
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    if (connected) Accent else TextDim,
                    CircleShape,
                ),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = statusText,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = modeLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            color = TextDim,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onButtonClick,
            enabled = buttonEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) Danger else Accent,
                contentColor = Bg,
                disabledContainerColor = Border,
                disabledContentColor = TextDim,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = buttonText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
