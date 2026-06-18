package com.zerovpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerovpn.app.ui.components.StatusCard
import com.zerovpn.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
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
        // Section title
        Text(
            text = "STATUS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        // Status card
        StatusCard(
            statusText = "Disconnected",
            modeLabel = "No mode selected",
            buttonText = "Connect",
            onButtonClick = {
                scope.launch {
                    snackbarHostState.showSnackbar("No exit configured")
                }
            },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Exits section
        Text(
            text = "EXITS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )

        // Empty exit list
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.clickable {
                        scope.launch {
                            snackbarHostState.showSnackbar("No exit configured")
                        }
                    },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add Exit",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Accent,
                    )
                }
            }
        }
    }
}