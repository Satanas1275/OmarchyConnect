package com.omarchyconnect.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarchyconnect.app.R
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.OcTheme

/** JetBrains Mono : la police de l'app, la même famille que le terminal Omarchy. */
val Mono: FontFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_bold, FontWeight.Bold)
)

fun ocColor(argb: Int): Color = Color(argb)

private fun scheme(t: OcTheme) = darkColorScheme(
    primary = ocColor(t.accent),
    onPrimary = ocColor(t.background),
    secondary = ocColor(t.borderAccent),
    onSecondary = ocColor(t.background),
    background = ocColor(t.background),
    onBackground = ocColor(t.text),
    surface = ocColor(t.surface),
    onSurface = ocColor(t.text),
    surfaceVariant = ocColor(t.surfaceAlt),
    onSurfaceVariant = ocColor(t.muted),
    outline = ocColor(t.border),
    outlineVariant = ocColor(t.border),
    error = ocColor(t.danger),
    onError = ocColor(t.background)
)

private val ocTypography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 12.sp)
)

/**
 * Thème Omarchy pour Compose : fond sombre, peu (voire pas) d'arrondis,
 * contours colorés, police monospace. Les couleurs sont recalculées à chaque
 * synchro de thème depuis le PC.
 */
@Composable
fun OmarchyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    @Suppress("UNUSED_EXPRESSION")
    darkTheme // le PC impose toujours le sombre
    val theme by Link.theme.collectAsState()

    MaterialTheme(
        colorScheme = scheme(theme),
        typography = ocTypography,
        shapes = androidx.compose.material3.Shapes(
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp)
        ),
        content = content
    )
}
