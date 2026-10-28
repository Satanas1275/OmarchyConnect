package com.omarchyconnect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.Prefs
import com.omarchyconnect.app.ui.screens.CommandsScreen
import com.omarchyconnect.app.ui.screens.HomeScreen
import com.omarchyconnect.app.ui.screens.PairScreen
import com.omarchyconnect.app.ui.screens.ProfileScreen
import com.omarchyconnect.app.ui.screens.RemoteScreen
import com.omarchyconnect.app.ui.screens.SettingsScreen
import com.omarchyconnect.app.ui.screens.WelcomeScreen

enum class Route(val label: String, val icon: ImageVector?) {
    WELCOME("Welcome", null),
    PAIR("Pair", null),
    HOME("Home", Icons.Outlined.Home),
    COMMANDS("Commands", Icons.Outlined.Code),
    REMOTE("Remote", Icons.Outlined.TouchApp),
    SETTINGS("Settings", Icons.Outlined.Settings),
    PROFILE("Profile", Icons.Outlined.Person)
}

/** Racine de l'UI : navigation simple (pas de dépendance navigation-compose). */
@Composable
fun OmarchyApp() {
    val start = if (Prefs.paired || Link.demo.value) Route.HOME else Route.WELCOME
    var route by remember { mutableStateOf(start) }
    val scheme = MaterialTheme.colorScheme

    fun go(r: Route) {
        route = r
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (route) {
                Route.WELCOME -> WelcomeScreen(onStart = { go(Route.PAIR) })
                Route.PAIR -> PairScreen(
                    onBack = { go(Route.WELCOME) },
                    onDone = { go(Route.HOME) }
                )
                Route.HOME -> HomeScreen(onNavigate = ::go)
                Route.COMMANDS -> CommandsScreen()
                Route.REMOTE -> RemoteScreen()
                Route.SETTINGS -> SettingsScreen(onNavigate = ::go)
                Route.PROFILE -> ProfileScreen(onNavigate = ::go)
            }
        }

        val mainRoutes = listOf(Route.HOME, Route.COMMANDS, Route.REMOTE, Route.SETTINGS, Route.PROFILE)
        if (route in mainRoutes) {
            OcBottomBar(current = route, onSelect = ::go)
        }
    }
}

@Composable
private fun OcBottomBar(current: Route, onSelect: (Route) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
                androidx.compose.ui.graphics.RectangleShape
            )
            .height(58.dp)
    ) {
        listOf(Route.HOME, Route.COMMANDS, Route.REMOTE, Route.SETTINGS, Route.PROFILE).forEach { r ->
            val selected = r == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(if (selected) scheme.surfaceVariant else scheme.surface)
                    .clickable { onSelect(r) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = r.icon ?: Icons.Outlined.Home,
                    contentDescription = r.label,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = r.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant
                )
            }
        }
    }
}
