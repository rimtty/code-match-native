package jp.rimtty.codematch

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import jp.rimtty.codematch.history.HistoryRoute
import jp.rimtty.codematch.scan.ScanRoute
import jp.rimtty.codematch.settings.SettingsRoute

private enum class AppDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    SCAN("scan", R.string.destination_scan, Icons.Outlined.QrCodeScanner),
    HISTORY("history", R.string.destination_history, Icons.Outlined.History),
    SETTINGS("settings", R.string.destination_settings, Icons.Outlined.Settings),
    ;

    companion object {
        fun fromRoute(route: String): AppDestination =
            entries.firstOrNull { it.route == route } ?: SCAN
    }
}

/** M2 application shell backed by repositories and stateless feature UIs. */
@Composable
fun CodeMatchApp() {
    var selectedRoute by rememberSaveable { mutableStateOf(AppDestination.SCAN.route) }
    val selected = AppDestination.fromRoute(selectedRoute)

    NavigationSuiteScaffold(
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
            androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2(),
        ),
        navigationSuiteItems = {
            AppDestination.entries.forEach { destination ->
                item(
                    selected = selected == destination,
                    onClick = { selectedRoute = destination.route },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = stringResource(destination.labelRes),
                        )
                    },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (selected) {
            AppDestination.SCAN -> ScanRoute(
                modifier = Modifier.fillMaxSize(),
                showDebugDemoTools = booleanResource(R.bool.show_debug_demo_tools),
            )
            AppDestination.HISTORY -> HistoryRoute(Modifier.fillMaxSize())
            AppDestination.SETTINGS -> SettingsRoute(Modifier.fillMaxSize())
        }
    }
}
