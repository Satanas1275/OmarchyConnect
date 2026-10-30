package com.omarchyconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.Command
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.Protocol
import com.omarchyconnect.app.ui.components.OcButton
import com.omarchyconnect.app.ui.components.Panel
import com.omarchyconnect.app.ui.components.SectionLabel
import org.json.JSONObject

/**
 * Commandes enregistrées sur le PC (omarchy-connect.toml).
 * Chaque ligne devient un bouton : le nom + la description viennent du PC,
 * l'exécution se fait sur le PC.
 */
@Composable
fun CommandsScreen() {
    val scheme = MaterialTheme.colorScheme
    val commands by Link.commands.collectAsState()
    val result by Link.lastResult.collectAsState()
    val status by Link.status.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Commands",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onBackground
                )
                Text(
                    text = if (status == com.omarchyconnect.app.core.LinkStatus.ONLINE)
                        "${commands.size} registered on ${Link.pcName.value.ifBlank { "the PC" }}"
                    else "offline · connect to load the commands registered on the PC",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            OcButton(
                text = "sync",
                height = 36.dp,
                onClick = { Link.send(Protocol.T_COMMANDS_LIST, JSONObject()) },
                modifier = Modifier.width(84.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        commands.forEach { cmd ->
            CommandCard(cmd)
            Spacer(Modifier.height(10.dp))
        }

        if (commands.isEmpty()) {
            Panel {
                Text(
                    text = "> no command yet.\n> add them in ~/.config/omarchy-connect/commands.toml on the PC, then press sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("last output")
        Spacer(Modifier.height(8.dp))
        Panel(accent = result != null, padding = 12.dp) {
            if (result == null) {
                Text(
                    text = "> run a command to see its output here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            } else {
                val r = result!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (r.ok) "ok" else "err",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (r.ok) scheme.primary else scheme.error
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "$ ${r.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = r.out.ifBlank { "(no output)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CommandCard(cmd: Command) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, if (cmd.danger) scheme.error.copy(alpha = 0.6f) else scheme.outline),
                RectangleShape
            )
            .background(scheme.surface, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "> ${cmd.name}",
                style = MaterialTheme.typography.titleSmall,
                color = if (cmd.danger) scheme.error else scheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = cmd.description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        OcButton(
            text = if (cmd.danger) "run!" else "run",
            height = 38.dp,
            danger = cmd.danger,
            accent = !cmd.danger,
            onClick = { Link.runCommand(cmd) },
            modifier = Modifier.width(74.dp)
        )
    }
}
