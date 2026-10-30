package com.omarchyconnect.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarchyconnect.app.core.LogLine
import com.omarchyconnect.app.ui.theme.Mono
import com.omarchyconnect.app.ui.theme.ocColor

/** Conteneur "carte" : fond de surface, contour fin 1dp, angles droits. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (accent) scheme.primary else scheme.outline
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, border), RectangleShape)
            .background(scheme.surface, RectangleShape)
            .padding(padding),
        content = content
    )
}

/** Micro-titre de section, façon annotation du mockup. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.4.sp,
        modifier = modifier
    )
}

/** Bouton contour (le style "Get started" / "Login" du mockup). */
@Composable
fun OcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 48.dp,
    accent: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val border = when {
        danger -> scheme.error
        accent -> scheme.primary
        else -> scheme.outline
    }
    val fg = when {
        danger -> scheme.error
        accent -> scheme.primary
        else -> scheme.onSurface
    }
    Row(
        modifier = modifier
            .height(height)
            .border(BorderStroke(1.dp, if (enabled) border else scheme.outline.copy(alpha = 0.5f)), RectangleShape)
            .background(if (accent) scheme.surfaceVariant else scheme.surface, RectangleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) fg else fg.copy(alpha = 0.45f)
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Champ de saisie monospace avec contour fin. */
@Composable
fun OcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (singleLine) 48.dp else 110.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                BorderStroke(1.dp, if (focused) scheme.primary else scheme.outline),
                RectangleShape
            )
            .background(scheme.surfaceVariant, RectangleShape)
            .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 10.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = { onImeAction?.invoke() },
                onDone = { onImeAction?.invoke() },
                onNext = { onImeAction?.invoke() }
            ),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Interrupteur "terminal" : [x] on / [ ] off. */
@Composable
fun OcToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(width = 54.dp, height = 26.dp)
                .border(
                    BorderStroke(1.dp, if (checked) scheme.primary else scheme.outline),
                    RectangleShape
                )
                .background(
                    if (checked) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceVariant,
                    RectangleShape
                )
                .clickable { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (checked) "[x]" else "[ ]",
                style = MaterialTheme.typography.labelMedium,
                color = if (checked) scheme.primary else scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 9.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RectangleShape)
            .background(color)
    )
}

/** Puce de sélection (Mouse / Keyboard / Both). */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .border(
                BorderStroke(1.dp, if (selected) scheme.primary else scheme.outline),
                RectangleShape
            )
            .background(
                if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.surface,
                RectangleShape
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant
        )
    }
}

fun levelColor(level: String): Color = when (level) {
    "ok" -> ocColor(0xFFA8E6CF.toInt())
    "err" -> ocColor(0xFFE88C8C.toInt())
    "warn" -> ocColor(0xFFF0C987.toInt())
    "net" -> ocColor(0xFF9ECBFF.toInt())
    else -> ocColor(0xFF7C8A99.toInt())
}

/** Journal d'activité, rendu comme une sortie de terminal. */
@Composable
fun LogView(
    lines: List<LogLine>,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    val scheme = MaterialTheme.colorScheme
    val state = remember { ScrollState(0) }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollTo(state.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(state)
    ) {
        lines.takeLast(maxLines).forEach { line ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = line.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line.level.padEnd(4),
                    style = MaterialTheme.typography.bodySmall,
                    color = levelColor(line.level)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 2
                )
            }
        }
    }
}

/** Avatar initiales (menthe sur fond sombre), comme sur le mockup. */
@Composable
fun Avatar(name: String, size: androidx.compose.ui.unit.Dp = 64.dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val initials = name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .take(2)
        .ifEmpty { "OC" }
        .uppercase()
    Box(
        modifier = modifier
            .size(size)
            .clip(RectangleShape)
            .background(scheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = Mono,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ),
            color = scheme.background,
            textAlign = TextAlign.Center
        )
    }
}
