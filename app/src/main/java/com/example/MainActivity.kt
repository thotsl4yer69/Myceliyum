package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.Species
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.FungiViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val fungiViewModel = ViewModelProvider(this, FungiViewModel.provideFactory(application))[FungiViewModel::class.java]

        setContent {
            val appTheme by fungiViewModel.appTheme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (appTheme) {
                "Light" -> false
                "Dark" -> true
                else -> systemDark // "System"
            }
            MyApplicationTheme(darkTheme = useDark) {
                MainWorkflowLayout(viewModel = fungiViewModel)
            }
        }
    }
}

sealed interface SelectedTab {
    object Home : SelectedTab
    object Search : SelectedTab
    object Map : SelectedTab
    object Identify : SelectedTab
    object Sightings : SelectedTab
    object Settings : SelectedTab
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainWorkflowLayout(viewModel: FungiViewModel) {
    // Nav Navigation tabs states
    var activeTab by remember { mutableStateOf<SelectedTab>(SelectedTab.Home) }
    var activeDetailSpecies by remember { mutableStateOf<Species?>(null) }

    val splashNoticeAccepted by viewModel.splashNoticeAccepted.collectAsState()

    // 1. Mandatory Single-Launch Medical & Scientific Disclaimer Notice
    if (!splashNoticeAccepted) {
        SplashNoticeDialog(
            onDismiss = {
                viewModel.acceptSplashNotice()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.testTag("app_bottom_nav"),
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                ) {
                    NavigationBarItem(
                        selected = activeTab is SelectedTab.Home,
                        onClick = {
                            activeTab = SelectedTab.Home
                            activeDetailSpecies = null
                        },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        modifier = Modifier.testTag("nav_home")
                    )

                    NavigationBarItem(
                        selected = activeTab is SelectedTab.Search,
                        onClick = {
                            activeTab = SelectedTab.Search
                            activeDetailSpecies = null
                        },
                        icon = { Icon(Icons.Default.ManageSearch, contentDescription = "Search Catalogue") },
                        label = { Text("Taxa") },
                        modifier = Modifier.testTag("nav_search")
                    )

                    NavigationBarItem(
                        selected = activeTab is SelectedTab.Map,
                        onClick = {
                            activeTab = SelectedTab.Map
                            activeDetailSpecies = null
                        },
                        icon = { Icon(Icons.Default.Explore, contentDescription = "Hotspot Predictor") },
                        label = { Text("Hotspots") },
                        modifier = Modifier.testTag("nav_map")
                    )

                    NavigationBarItem(
                        selected = activeTab is SelectedTab.Identify,
                        onClick = {
                            activeTab = SelectedTab.Identify
                            activeDetailSpecies = null
                        },
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = "AI Identify") },
                        label = { Text("Identify") },
                        modifier = Modifier.testTag("nav_identify")
                    )

                    NavigationBarItem(
                        selected = activeTab is SelectedTab.Sightings,
                        onClick = {
                            activeTab = SelectedTab.Sightings
                            activeDetailSpecies = null
                        },
                        icon = { Icon(Icons.Default.History, contentDescription = "Sightings Registry") },
                        label = { Text("Log") },
                        modifier = Modifier.testTag("nav_sightings")
                    )

                    // Settings is accessed via the gear icon in the Home top bar
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Render selected workflow tab
                when (activeTab) {
                    is SelectedTab.Home -> HomeScreen(
                        viewModel = viewModel,
                        onNavigateToSearch = { activeTab = SelectedTab.Search },
                        onNavigateToMap = { activeTab = SelectedTab.Map },
                        onNavigateToSightings = { activeTab = SelectedTab.Sightings },
                        onNavigateToSettings = { activeTab = SelectedTab.Settings },
                        onSpeciesSelected = { spec ->
                            activeDetailSpecies = spec
                        }
                    )
                    is SelectedTab.Search -> SearchScreen(
                        viewModel = viewModel,
                        onSpeciesSelected = { spec ->
                            activeDetailSpecies = spec
                        }
                    )
                    is SelectedTab.Map -> MapScreen(
                        viewModel = viewModel
                    )
                    is SelectedTab.Identify -> IdentifyScreen()
                    is SelectedTab.Sightings -> SightingsScreen(
                        viewModel = viewModel
                    )
                    is SelectedTab.Settings -> SettingsScreen(
                        viewModel = viewModel
                    )
                }

                // 2. High-fidelity slide-over Species Detail Screen when a details request is focal!
                AnimatedVisibility(
                    visible = activeDetailSpecies != null,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    activeDetailSpecies?.let { focalSpecies ->
                        DetailScreen(
                            species = focalSpecies,
                            viewModel = viewModel,
                            onNavigateToMap = {
                                activeTab = SelectedTab.Map
                                activeDetailSpecies = null
                            },
                            onBack = {
                                activeDetailSpecies = null
                            }
                        )
                    }
                }
            }
        }
    }
}
