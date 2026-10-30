package com.omarchyconnect.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.LinkStatus
import com.omarchyconnect.app.ui.Route
import com.omarchyconnect.app.ui.components.Avatar
import com.omarchyconnect.app.ui.components.LogView
import com.omarchyconnect.app.ui.components.Panel
import com.omarchyconnect.app.ui.components.SectionLabel
import com.omarchyconnect.app.ui.components.StatusDot
import com.omarchyconnect.app.ui.components.levelColor

@Composable
fun HomeScreen(onNavigate: (Route) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    val status by Link.status.collectAsState()
    val user by Link.user.collectAsState()
    val pcName by Link.pcName.collectAsState()
    val domain by Link.domain.collectAsState()
    val latency by Link.latency.collectAsState()
    val logLines by Link.log.collectAsState()
    val busy by Link.busy.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) Link.sendFiles(context, uris)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        // --- En-tête ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Welcome",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    text = "$user !",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
            }
            Avatar(name = user, size = 48.dp, modifier = Modifier.clickable { onNavigate(Route.PROFILE) })
        }

        Spacer(Modifier.height(16.dp))

        // --- Lien ---
        Panel(accent = status == LinkStatus.ONLINE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dot, label) = when {
                    status == LinkStatus.ONLINE -> scheme.primary to "linked"
                    status == LinkStatus.PAIRING -> levelColor("warn") to "pairing…"
                    status == LinkStatus.CONNECTING -> levelColor("warn") to "connecting…"
                    status == LinkStatus.ERROR -> scheme.error to "error"
                    else -> scheme.onSurfaceVariant to "offline"
                }
                StatusDot(dot)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = dot
                )
                Spacer(Modifier.weight(1f))
                if (status == LinkStatus.ONLINE && latency > 0) {
                    Text(
                        text = "${latency} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            KeyValue("pc", pcName.ifBlank { "—" })
            KeyValue("host", domain.ifBlank { "—" })
            KeyValue("transport", Link.transport.value)
            KeyValue("forward", "notifications · clipboard · input")
        }

        Spacer(Modifier.height(16.dp))

        // --- Actions rapides ---
        SectionLabel("quick actions")
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Outlined.Code, null, tint = scheme.primary, modifier = Modifier.size(20.dp)) },
                label = "Commands",
                hint = "run on PC",
                onClick = { onNavigate(Route.COMMANDS) }
            )
            Tile(
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Outlined.TouchApp, null, tint = scheme.primary, modifier = Modifier.size(20.dp)) },
                label = "Remote",
                hint = "mouse + keys",
                onClick = { onNavigate(Route.REMOTE) }
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Outlined.FileCopy, null, tint = scheme.primary, modifier = Modifier.size(20.dp)) },
                label = if (busy) "Sending…" else "Send file",
                hint = "to the PC",
                enabled = !busy,
                onClick = { picker.launch(arrayOf("*/*")) }
            )
            Tile(
                modifier = Modifier.weight(1f),
                icon = { Icon(Icons.Outlined.ContentPaste, null, tint = scheme.primary, modifier = Modifier.size(20.dp)) },
                label = "Clipboard",
                hint = "sync is in settings",
                onClick = { onNavigate(Route.SETTINGS) }
            )
        }

        Spacer(Modifier.height(18.dp))

        // --- Journal ---
        SectionLabel("activity")
        Spacer(Modifier.height(8.dp))
        Panel(padding = 12.dp) {
            LogView(
                lines = logLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 2.dp)) {
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

@Composable
private fun Tile(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    hint: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .height(96.dp)
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
                RectangleShape
            )
            .background(scheme.surface, RectangleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}
