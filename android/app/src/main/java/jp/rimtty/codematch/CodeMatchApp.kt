package jp.rimtty.codematch

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import jp.rimtty.codematch.core.designsystem.CodeMatchColors

private sealed interface AppDestination {
    val route: String

    @get:StringRes
    val labelRes: Int

    @get:StringRes
    val titleRes: Int

    val icon: ImageVector

    data object Scan : AppDestination {
        override val route: String = "scan"
        override val labelRes: Int = R.string.destination_scan
        override val titleRes: Int = R.string.destination_scan
        override val icon: ImageVector = Icons.Outlined.QrCodeScanner
    }

    data object History : AppDestination {
        override val route: String = "history"
        override val labelRes: Int = R.string.destination_history
        override val titleRes: Int = R.string.destination_history
        override val icon: ImageVector = Icons.Outlined.History
    }

    data object Settings : AppDestination {
        override val route: String = "settings"
        override val labelRes: Int = R.string.destination_settings
        override val titleRes: Int = R.string.destination_settings
        override val icon: ImageVector = Icons.Outlined.Settings
    }

    companion object {
        fun fromRoute(route: String): AppDestination = when (route) {
            History.route -> History
            Settings.route -> Settings
            else -> Scan
        }
    }
}

private val topLevelDestinations = listOf(
    AppDestination.Scan,
    AppDestination.History,
    AppDestination.Settings,
)

@Composable
fun CodeMatchApp() {
    var selectedRoute by rememberSaveable { mutableStateOf(AppDestination.Scan.route) }
    val backStack = remember {
        mutableStateListOf<Any>(AppDestination.fromRoute(selectedRoute))
    }
    val currentDestination = backStack.lastOrNull() as? AppDestination ?: AppDestination.Scan

    NavigationSuiteScaffold(
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
            androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2(),
        ),
        navigationSuiteItems = {
            topLevelDestinations.forEach { destination ->
                item(
                    selected = currentDestination == destination,
                    onClick = {
                        if (currentDestination != destination) {
                            selectedRoute = destination.route
                            backStack.clear()
                            backStack.add(destination)
                        }
                    },
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
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            entryProvider = { key ->
                when (key) {
                    AppDestination.Scan -> NavEntry(key) { ScanDestination() }
                    AppDestination.History -> NavEntry(key) { HistoryDestination() }
                    AppDestination.Settings -> NavEntry(key) { SettingsDestination() }
                    else -> error("Unknown destination: $key")
                }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DestinationScaffold(
    title: String,
    contentDescription: String,
    content: @Composable (innerPadding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }) },
        modifier = Modifier
            .fillMaxSize()
            .semantics { this.contentDescription = contentDescription },
        content = content,
    )
}

@Composable
private fun ScanDestination() {
    var sessionName by rememberSaveable { mutableStateOf("") }
    var sessionStarted by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.destination_scan)

    DestinationScaffold(
        title = title,
        contentDescription = stringResource(R.string.scan_screen_description),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scan_eyebrow),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.scan_headline),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.scan_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sessionName,
                onValueChange = { sessionName = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !sessionStarted,
                label = { Text(stringResource(R.string.session_name_label)) },
                placeholder = { Text(stringResource(R.string.session_name_placeholder)) },
                singleLine = true,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.scan_skeleton_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.scan_skeleton_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (sessionStarted) 0.33f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = { sessionStarted = !sessionStarted },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = stringResource(
                        if (sessionStarted) R.string.session_started else R.string.start_session,
                    ),
                )
            }
            if (sessionStarted) {
                Text(
                    text = stringResource(R.string.scan_waiting_for_qr),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CodeMatchColors.Green,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HistoryDestination() {
    DestinationScaffold(
        title = stringResource(R.string.destination_history),
        contentDescription = stringResource(R.string.history_screen_description),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.history_headline),
                style = MaterialTheme.typography.headlineMedium,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.history_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.history_empty_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDestination() {
    var autoAdvanceEnabled by rememberSaveable { mutableStateOf(false) }

    DestinationScaffold(
        title = stringResource(R.string.destination_settings),
        contentDescription = stringResource(R.string.settings_screen_description),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_headline),
                style = MaterialTheme.typography.headlineMedium,
            )
            Card(Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.auto_advance_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.auto_advance_description))
                        },
                        trailingContent = {
                            Switch(
                                checked = autoAdvanceEnabled,
                                onCheckedChange = { autoAdvanceEnabled = it },
                            )
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.language_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.language_description))
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_skeleton_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
