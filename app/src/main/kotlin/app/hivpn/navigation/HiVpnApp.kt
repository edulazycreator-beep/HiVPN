package app.hivpn.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.hivpn.R
import app.hivpn.feature.dashboard.DashboardRoute
import app.hivpn.feature.dashboard.dashboardScreen
import app.hivpn.feature.diagnostics.DiagnosticsRoute
import app.hivpn.feature.diagnostics.diagnosticsScreen
import app.hivpn.feature.onboarding.OnboardingRoute
import app.hivpn.feature.onboarding.onboardingScreen
import app.hivpn.feature.profiles.ProfileEditorRoute
import app.hivpn.feature.profiles.ProfilesRoute
import app.hivpn.feature.profiles.profilesGraph
import app.hivpn.feature.servers.ServersRoute
import app.hivpn.feature.servers.serversScreen
import app.hivpn.feature.settings.SettingsRoute
import app.hivpn.feature.settings.settingsScreen
import app.hivpn.feature.statistics.StatisticsRoute
import app.hivpn.feature.statistics.statisticsScreen
import kotlin.reflect.KClass

private enum class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    val label: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Home(DashboardRoute, DashboardRoute::class, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    Servers(ServersRoute, ServersRoute::class, R.string.nav_servers, Icons.Filled.Public, Icons.Outlined.Public),
    Stats(StatisticsRoute, StatisticsRoute::class, R.string.nav_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Settings(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun HiVpnApp(onboardingCompleted: Boolean, versionName: String) {
    val navController = rememberNavController()
    // Decided once: changing it later must not rebuild the graph mid-flow.
    val startDestination: Any = remember { if (onboardingCompleted) DashboardRoute else OnboardingRoute }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBar = TopLevel.entries.any { destination.isOn(it.routeClass) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TopLevel.entries.forEach { item ->
                        val selected = destination.isOn(item.routeClass)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(item.route) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = startDestination) {
                onboardingScreen(
                    onFinished = {
                        navController.navigate(DashboardRoute) {
                            popUpTo(OnboardingRoute) { inclusive = true }
                        }
                    },
                )
                dashboardScreen(onChooseServer = { navController.navigateTopLevel(ServersRoute) })
                serversScreen(onServerSelected = { navController.navigateTopLevel(DashboardRoute) })
                statisticsScreen()
                settingsScreen(
                    versionName = versionName,
                    onOpenProfiles = { navController.navigate(ProfilesRoute) },
                    onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) },
                )
                profilesGraph(
                    onBack = navController::popBackStack,
                    onEdit = { id -> navController.navigate(ProfileEditorRoute(id)) },
                )
                diagnosticsScreen(onBack = navController::popBackStack)
            }
        }
    }
}

private fun NavDestination?.isOn(route: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(route) } == true

private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
