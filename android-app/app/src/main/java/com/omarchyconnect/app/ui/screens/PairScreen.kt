package com.omarchyconnect.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.LinkStatus
import com.omarchyconnect.app.core.Prefs
import com.omarchyconnect.app.core.Protocol
import com.omarchyconnect.app.ui.components.OcButton
import com.omarchyconnect.app.ui.components.OcTextField
import com.omarchyconnect.app.ui.components.SectionLabel

@Composable
fun PairScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var domain by remember { mutableStateOf(Prefs.domain) }
    var code by remember { mutableStateOf("") }

    val status by Link.status.collectAsState()
    val error by Link.error.collectAsState()

    LaunchedEffect(status) {
        if (status == LinkStatus.ONLINE || (Link.demo.value && status == LinkStatus.OFFLINE)) {
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "<-",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp)
            )
            SectionLabel("pairing")
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Connect to your\nOmarchy PC",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Enter the domain exposed by your PC and the one-time code\nshown by omarchy-connect on that machine.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("domain")
        Spacer(Modifier.height(6.dp))
        OcTextField(
            value = domain,
            onValueChange = { domain = it },
            placeholder = "omarchy.example.com",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("pairing code")
        Spacer(Modifier.height(6.dp))
        OcTextField(
            value = code,
            onValueChange = { code = it },
            placeholder = "XXXX-XXXX",
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error
            )
        }

        Spacer(Modifier.height(22.dp))
        OcButton(
            text = when (status) {
                LinkStatus.PAIRING -> "Waiting for confirmation…"
                LinkStatus.CONNECTING -> "Connecting…"
                else -> "Pair"
            },
            accent = true,
            enabled = status != LinkStatus.PAIRING && status != LinkStatus.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                Link.domain.value = Protocol.normalizeDomain(domain)
                Link.pair(code)
            }
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = "Continue without an account",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    Link.enableDemo()
                    onDone()
                }
                .padding(vertical = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("security")
        Spacer(Modifier.height(8.dp))
        listOf(
            "keystore: private key never leaves the device",
            "transport: wss:// only, cleartext disabled",
            "pairing: 6-digit code, 30s window, one-shot",
            "session: random token, encrypted at rest"
        ).forEach {
            Text(
                text = "> $it",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
