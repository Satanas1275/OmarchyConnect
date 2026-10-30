package com.omarchyconnect.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.DeviceIdentity
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.LinkStatus
import com.omarchyconnect.app.ui.Route
import com.omarchyconnect.app.ui.components.Avatar
import com.omarchyconnect.app.ui.components.OcButton
import com.omarchyconnect.app.ui.components.Panel
import com.omarchyconnect.app.ui.components.SectionLabel

@Composable
fun ProfileScreen(onNavigate: (Route) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val user by Link.user.collectAsState()
    val since by Link.userSince.collectAsState()
    val domain by Link.domain.collectAsState()
    val status by Link.status.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Avatar(name = user, size = 96.dp)

        Spacer(Modifier.height(14.dp))
        Text(
            text = user,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onBackground
        )
        Text(
            text = "User since $since",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("device", Modifier.align(Alignment.Start))
        Spacer(Modifier.height(8.dp))
        Panel(Modifier.fillMaxWidth()) {
            Info("phone", android.os.Build.MODEL ?: "Android")
            Info("android", "API ${android.os.Build.VERSION.SDK_INT}")
            Info("device id", rememberDeviceId())
            Info("key fp", DeviceIdentity.fingerprint())
            Info("paired", domain.ifBlank { "no" })
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("actions", Modifier.align(Alignment.Start))
        Spacer(Modifier.height(8.dp))
        Panel(Modifier.fillMaxWidth()) {
            OcButton(
                text = "Open settings",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Route.SETTINGS) }
            )
            Spacer(Modifier.height(10.dp))
            OcButton(
                text = if (status == LinkStatus.ONLINE) "Disconnect" else "Pair a PC",
                accent = status != LinkStatus.ONLINE,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (status == LinkStatus.ONLINE) Link.disconnect("profile")
                    onNavigate(Route.PAIR)
                }
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun rememberDeviceId(): String =
    androidx.compose.runtime.remember {
        com.omarchyconnect.app.core.Prefs.deviceId.ifBlank { "not assigned yet" }
            .let { if (it.length > 18) it.take(18) + "…" else it }
    }

@Composable
private fun Info(key: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key.padEnd(10),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface.copy(alpha = 0.9f)
        )
    }
}
