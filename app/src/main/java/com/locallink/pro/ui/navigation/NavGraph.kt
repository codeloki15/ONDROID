package com.locallink.pro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.locallink.pro.ui.screens.chat.ChatScreen
import com.locallink.pro.ui.screens.connect.ConnectScreen
import com.locallink.pro.ui.screens.model.ModelGateScreen
import com.locallink.pro.ui.screens.sessions.SessionsScreen
import com.locallink.pro.ui.screens.settings.SettingsScreen

object Routes {
    const val GATE = "gate"
    const val SESSIONS = "sessions"
    const val CHAT = "chat"            // chat?sessionId={id}&voice={bool}
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
}

@Composable
fun LocalLinkNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.GATE) {
        composable(Routes.GATE) {
            ModelGateScreen(onReady = {
                navController.navigate(Routes.SESSIONS) {
                    popUpTo(Routes.GATE) { inclusive = true }
                }
            })
        }
        composable(Routes.SESSIONS) {
            SessionsScreen(
                onOpenSession = { id ->
                    // History and the FAB open in full-capability mode.
                    navController.navigate("${Routes.CHAT}?sessionId=${id ?: ""}&mode=auto")
                },
                onOpenChat = {
                    navController.navigate("${Routes.CHAT}?sessionId=&mode=chat")
                },
                onOpenVoice = {
                    navController.navigate("${Routes.CHAT}?sessionId=&voice=true&mode=voice")
                },
                onOpenAutomate = {
                    navController.navigate("${Routes.CHAT}?sessionId=&mode=auto")
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            "${Routes.CHAT}?sessionId={sessionId}&voice={voice}&mode={mode}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("voice") { type = NavType.BoolType; defaultValue = false },
                navArgument("mode") { type = NavType.StringType; defaultValue = "auto" },
            ),
        ) { entry ->
            val sid = entry.arguments?.getString("sessionId")?.ifBlank { null }
            val voice = entry.arguments?.getBoolean("voice") ?: false
            val mode = entry.arguments?.getString("mode") ?: "auto"
            ChatScreen(
                sessionId = sid,
                startVoice = voice,
                mode = mode,
                onBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageApps = { navController.navigate(Routes.CONNECT) },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = { navController.popBackStack() })
        }
    }
}
