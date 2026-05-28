package com.locallink.pro.ui.screens.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.locallink.pro.ui.screens.chat.ChatScreen
import com.locallink.pro.ui.screens.files.FileBrowserScreen
import com.locallink.pro.ui.screens.git.GitScreen
import com.locallink.pro.ui.screens.settings.SettingsScreen
import com.locallink.pro.ui.screens.terminal.TerminalScreen
import com.locallink.pro.ui.theme.*

object ShellRoutes {
    const val CHAT = "shell_chat"
    const val FILES = "shell_files"
    const val TERMINAL = "shell_terminal"
    const val GIT = "shell_git"
    const val SETTINGS = "shell_settings"
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem(ShellRoutes.CHAT, "Chat", Icons.Default.Forum),
    NavItem(ShellRoutes.FILES, "Files", Icons.Default.Folder),
    // Terminal and Git removed from navigation
)

@Composable
fun MainShellScreen(
    onDisconnect: () -> Unit
) {
    val nestedNavController = rememberNavController()
    val navBackStackEntry by nestedNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // No bottom bar anymore - navigation handled in each screen's header
    NavHost(
        navController = nestedNavController,
        startDestination = ShellRoutes.CHAT,
    ) {
        composable(ShellRoutes.CHAT) {
            ChatScreen(
                onNavigateToSettings = {
                    nestedNavController.navigate(ShellRoutes.SETTINGS)
                },
                onNavigateToFiles = {
                    nestedNavController.navigate(ShellRoutes.FILES) {
                        popUpTo(ShellRoutes.CHAT) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
        composable(ShellRoutes.FILES) {
            FileBrowserScreen(
                onNavigateToChat = {
                    nestedNavController.navigate(ShellRoutes.CHAT) {
                        popUpTo(ShellRoutes.FILES) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
        // Terminal and Git screens removed
        // composable(ShellRoutes.TERMINAL) {
        //     TerminalScreen()
        // }
        // composable(ShellRoutes.GIT) {
        //     GitScreen()
        // }
        composable(ShellRoutes.SETTINGS) {
            SettingsScreen(onBack = { nestedNavController.popBackStack() })
        }
    }
}
