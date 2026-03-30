package com.example.utt_trafficjams.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.utt_trafficjams.ui.components.UTTBottomNavigation
import com.example.utt_trafficjams.ui.screens.handbook.HandbookScreen
import com.example.utt_trafficjams.ui.screens.home.HomeScreen
import com.example.utt_trafficjams.ui.screens.routes.RoutesScreen
import com.example.utt_trafficjams.ui.theme.DarkBackground

// ==============================
// Main App Container
// Scaffold + Navigation Host + Bottom Nav
// ==============================

@Composable
fun UTTTrafficApp() {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route ?: "home"

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            UTTBottomNavigation(
                currentRoute = currentRoute,
                onItemClick = { route ->
                    if (route != currentRoute) {
                        navController.navigate(route) {
                            // Pop up to start destination to avoid large back stack
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen()
            }
            composable("routes") {
                RoutesScreen()
            }
            composable("handbook") {
                HandbookScreen()
            }
        }
    }
}
