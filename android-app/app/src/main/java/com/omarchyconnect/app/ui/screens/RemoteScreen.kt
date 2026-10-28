package com.omarchyconnect.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.ui.components.Chip
import com.omarchyconnect.app.ui.components.OcButton
import com.omarchyconnect.app.ui.components.OcTextField
import com.omarchyconnect.app.ui.components.Panel
import com.omarchyconnect.app.ui.components.SectionLabel

/**
 * Le téléphone en tant que souris et/ou clavier du PC.
 * Les deux peuvent être actifs en même temps ("both").
 */
@Composable
fun RemoteScreen() {
    val scheme = MaterialTheme.colorScheme
    var mode by rememberSaveable { mutableStateOf("both") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Text(
            text = "Remote input",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onBackground
        )
        Text(
            text = "use the phone as a mouse, a keyboard, or both at once",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("mouse", mode == "mouse" || mode == "both") {
                mode = if (mode == "keyboard") "both" else "mouse"
            }
            Chip("keyboard", mode == "keyboard" || mode == "both") {
                mode = if (mode == "mouse") "both" else "keyboard"
            }
            Chip("both", mode == "both") { mode = "both" }
        }

        Spacer(Modifier.height(14.dp))

        if (mode != "keyboard") {
            TouchPad()
            Spacer(Modifier.height(12.dp))
        }
        if (mode != "mouse") {
            KeyboardPad()
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "events are sent over the authenticated link only · no input is stored",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** Pavé tactile : glisser = déplacer, appui court = clic gauche, appui long = clic droit. */
@Composable
private fun TouchPad() {
    val scheme = MaterialTheme.colorScheme

    Panel(padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(scheme.surfaceVariant, RectangleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downAt = System.currentTimeMillis()
                            var dragging = false
                            var rightFired = false
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!dragging && !rightFired) {
                                        Link.input("click", button = "left")
                                    }
                                    break
                                }
                                val delta = change.position - last
                                if (!dragging && delta.getDistance() > 6f) dragging = true
                                if (dragging && delta != androidx.compose.ui.geometry.Offset.Zero) {
                                    Link.input("move", dx = delta.x, dy = delta.y)
                                    change.consume()
                                }
                                last = change.position
                                if (!dragging && !rightFired &&
                                    System.currentTimeMillis() - downAt > 450
                                ) {
                                    rightFired = true
                                    Link.input("click", button = "right")
                                    change.consume()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "drag = move\nhold = right click\ntap = left click",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            // Zone de défilement (colonne à droite)
            Box(
                Modifier
                    .width(52.dp)
                    .fillMaxHeight()
                    .background(scheme.surface, RectangleShape)
                    .border(
                        androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
                        RectangleShape
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { _, amount ->
                            Link.input("scroll", dy = -amount.y / 8f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "scroll",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OcButton(
                text = "left click",
                height = 40.dp,
                modifier = Modifier.weight(1f),
                onClick = { Link.input("click", button = "left") }
            )
            OcButton(
                text = "right click",
                height = 40.dp,
                modifier = Modifier.weight(1f),
                onClick = { Link.input("click", button = "right") }
            )
            OcButton(
                text = "middle",
                height = 40.dp,
                modifier = Modifier.weight(1f),
                onClick = { Link.input("click", button = "middle") }
            )
        }
    }
}

/** Clavier : texte envoyé en une fois + touches spéciales. */
@Composable
private fun KeyboardPad() {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }

    fun send() {
        if (text.isNotBlank()) Link.input("text", text = text)
        text = ""
    }

    Panel {
        SectionLabel("keyboard")
        Spacer(Modifier.height(10.dp))

        OcTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "type here, then send…",
            imeAction = androidx.compose.ui.text.input.ImeAction.Send,
            onImeAction = { send() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OcButton(
                text = "send",
                accent = true,
                height = 42.dp,
                modifier = Modifier.weight(1f),
                onClick = { send() }
            )
            OcButton(
                text = "clear",
                height = 42.dp,
                modifier = Modifier.weight(1f),
                onClick = { text = "" }
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("special keys")
        Spacer(Modifier.height(8.dp))

        val rows = listOf(
            listOf("Escape" to "esc", "Tab" to "tab", "Return" to "enter", "BackSpace" to "back"),
            listOf("Left" to "<", "Right" to ">", "Up" to "^", "Down" to "v"),
            listOf("ctrl_l" to "ctrl", "alt_l" to "alt", "super_l" to "super", "space" to "space")
        )
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (key, label) ->
                    Chip(text = label, selected = false, modifier = Modifier.weight(1f)) {
                        Link.input("key", key = key)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "backspace hold:",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            OcButton(
                text = "delete word",
                height = 36.dp,
                onClick = { repeat(5) { Link.input("key", key = "BackSpace") } }
            )
        }
    }
}
