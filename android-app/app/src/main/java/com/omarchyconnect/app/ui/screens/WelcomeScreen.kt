package com.omarchyconnect.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.ui.components.OcButton
import com.omarchyconnect.app.ui.theme.Mono

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Welcome to",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Omarchy\nConnect",
                style = MaterialTheme.typography.displaySmall,
                color = scheme.onBackground
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Link your Android device to your Omarchy machine: notifications, " +
                    "files, clipboard, commands and remote input — all over one encrypted channel.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            listOf(
                "-> notifications forwarded to the PC",
                "-> files & realtime clipboard sync",
                "-> run the commands you registered",
                "-> use the phone as mouse + keyboard"
            ).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        OcButton(
            text = "Get started",
            onClick = onStart,
            accent = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "tls enforced · keystore identity · no telemetry",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
    }
}
