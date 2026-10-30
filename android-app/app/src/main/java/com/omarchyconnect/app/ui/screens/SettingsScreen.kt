package com.omarchyconnect.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.DeviceIdentity
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.LinkStatus
import com.omarchyconnect.app.core.Prefs
import com.omarchyconnect.app.core.Protocol
import com.omarchyconnect.app.ui.Route
import com.omarchyconnect.app.ui.components.OcButton
import com.omarchyconnect.app.ui.components.OcTextField
import com.omarchyconnect.app.ui.components.OcToggle
import com.omarchyconnect.app.ui.components.Panel
import com.omarchyconnect.app.ui.components.SectionLabel
import org.json.JSONObject

@Composable
fun SettingsScreen(onNavigate: (Route) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    val status by Link.status.collectAsState()
    val domain by Link.domain.collectAsState()
    val themeSynced by Link.theme.collectAsState()
    val pcName by Link.pcName.collectAsState()
    val transport by Link.transport.collectAsState()
    val relayBase by Link.relayBase.collectAsState()
    val btDevices by Link.btDevices.collectAsState()

    var clipboardSync by remember { mutableStateOf(Prefs.clipboardSync) }
    var forwardNotifications by remember { mutableStateOf(Prefs.forwardNotifications) }
    var themeSync by remember { mutableStateOf(Prefs.themeSync) }
    var remoteInput by remember { mutableStateOf(Prefs.remoteInput) }
    var endpoint by remember { mutableStateOf(relayBase) }

    val listenerEnabled = remember {
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )?.contains(context.packageName) == true
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onBackground
        )
        Text(
            text = "synchronisation, sécurité et appareil",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("synchronisation")
        Spacer(Modifier.height(8.dp))
        Panel {
            OcToggle(
                checked = clipboardSync,
                onCheckedChange = {
                    clipboardSync = it
                    Prefs.clipboardSync = it
                },
                label = "Clipboard · realtime sync",
                description = "partage le presse-papiers dans les deux sens, en direct"
            )
            Divider()
            OcToggle(
                checked = forwardNotifications,
                onCheckedChange = {
                    forwardNotifications = it
                    Prefs.forwardNotifications = it
                },
                label = "Forward notifications",
                description = "pousse les notifications du téléphone vers le PC"
            )
            Divider()
            OcToggle(
                checked = themeSync,
                onCheckedChange = {
                    themeSync = it
                    Prefs.themeSync = it
                    if (it) Link.send(Protocol.T_THEME_GET, JSONObject())
                },
                label = "Theme sync with Omarchy",
                description = "reprend les couleurs du thème du PC (actuel: ${themeSynced.name})"
            )
            Divider()
            OcToggle(
                checked = remoteInput,
                onCheckedChange = {
                    remoteInput = it
                    Prefs.remoteInput = it
                },
                label = "Remote input (mouse + keyboard)",
                description = "autorise le pavé tactile et le clavier distant"
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("connection")
        Spacer(Modifier.height(8.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (domain.isBlank()) "no endpoint" else domain,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = status.name.lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (status) {
                        LinkStatus.ONLINE -> scheme.primary
                        LinkStatus.ERROR -> scheme.error
                        else -> scheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = transport,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("relay endpoint")
            Spacer(Modifier.height(6.dp))
            OcTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                placeholder = Prefs.DEFAULT_RELAY
            )
            Spacer(Modifier.height(8.dp))
            OcButton(
                text = "save endpoint",
                height = 38.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = { endpoint = Link.setEndpoint(endpoint) }
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                OcButton(
                    text = if (status == LinkStatus.ONLINE) "disconnect" else "reconnect",
                    height = 42.dp,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (status == LinkStatus.ONLINE) Link.disconnect("settings")
                        else Link.connect()
                    }
                )
                OcButton(
                    text = "forget device",
                    height = 42.dp,
                    danger = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        Link.forget()
                        onNavigate(Route.WELCOME)
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            OcButton(
                text = "connect via Bluetooth",
                height = 40.dp,
                accent = status != LinkStatus.ONLINE && btDevices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { Link.connectBluetooth() }
            )
            if (btDevices.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "no bonded Bluetooth device — pair the PC in Android settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            } else {
                btDevices.forEach { d ->
                    Text(
                        text = "> $d",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("notifications")
        Spacer(Modifier.height(8.dp))
        Panel {
            Text(
                text = if (listenerEnabled)
                    "notification access: granted"
                else
                    "notification access: required to forward alerts to the PC",
                style = MaterialTheme.typography.bodySmall,
                color = if (listenerEnabled) scheme.onSurfaceVariant else scheme.error
            )
            Spacer(Modifier.height(10.dp))
            OcButton(
                text = if (listenerEnabled) "open settings" else "grant access",
                accent = !listenerEnabled,
                height = 42.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("security")
        Spacer(Modifier.height(8.dp))
        Panel {
            InfoLine("transport", "relay wss:// · bluetooth RFCOMM/SPP")
            InfoLine("identity", "EC P-256 in Android Keystore")
            InfoLine("e2e", "ECDH P-256 + HKDF + AES-256-GCM")
            InfoLine("relay", "signed nonce · opaque payloads")
            InfoLine("key fp", remember { DeviceIdentity.fingerprint() })
            InfoLine("backup", "disabled (no cloud extraction)")
            InfoLine("telemetry", "none · no analytics, no 3rd party sdk")
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("about")
        Spacer(Modifier.height(8.dp))
        Panel {
            InfoLine("app", "OmarchyConnect 1.0.0")
            InfoLine("proto", "v1 · E2E json")
            InfoLine("mode", if (status == LinkStatus.ONLINE) "linked to ${pcName.ifBlank { domain }}" else "offline")
            InfoLine("build", "android · kotlin · compose")
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Divider() {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(scheme.outline.copy(alpha = 0.6f))
    )
}

@Composable
private fun InfoLine(key: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key.padEnd(9),
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
