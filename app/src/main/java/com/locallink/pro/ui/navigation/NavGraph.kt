package com.locallink.pro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.locallink.pro.ui.screens.connection.ConnectionScreen
import com.locallink.pro.ui.screens.shell.MainShellScreen

object Routes {
    const val CONNECTION = "connection"
    const val MAIN = "main"
}

@Composable
fun LocalLinkNavGraph(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Routes.CONNECTION
    ) {
        composable(Routes.CONNECTION) {
            ConnectionScreen(
                onConnected = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.CONNECTION) { inclusive = false }
                    }
                }
            )
        }

        composable(Routes.MAIN) {
            MainShellScreen(
                onDisconnect = {
                    navController.navigate(Routes.CONNECTION) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }
    }
}
