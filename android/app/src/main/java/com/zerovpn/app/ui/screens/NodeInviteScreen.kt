package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerovpn.app.ui.theme.*

@Composable
fun NodeInviteScreen(
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
            text = "NODES & INVITES",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No exits configured",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextDim,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Bg,
                        disabledContainerColor = Surface,
                        disabledContentColor = TextDim,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Generate Invite",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}