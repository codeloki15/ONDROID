package com.locallink.pro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.ui.screens.chat.ChatScreen
import com.locallink.pro.ui.screens.connect.ConnectScreen
import com.locallink.pro.ui.screens.model.ModelGateScreen
import com.locallink.pro.ui.screens.onboarding.OnboardingScreen
import com.locallink.pro.ui.screens.onboarding.OnboardingViewModel
import com.locallink.pro.ui.screens.routines.RoutinesScreen
import com.locallink.pro.ui.screens.sessions.SessionsScreen
import com.locallink.pro.ui.screens.settings.SettingsScreen

object Routes {
    const val GATE = "gate"
    const val ONBOARDING = "onboarding"
    const val SESSIONS = "sessions"
    const val CHAT = "chat"            // chat?sessionId={id}&voice={bool}
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val ROUTINES = "routines"
}

@Composable
fun LocalLinkNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.GATE) {
        composable(Routes.GATE) {
            // First run goes through the setup wizard; afterwards straight to home.
            // Navigate only once BOTH the model check and the async onboarding-flag
            // read have landed — onReady alone raced the DataStore read (null →
            // wizard shown on every cold start).
            val obVm: OnboardingViewModel = hiltViewModel()
            val onboarded by obVm.done.collectAsState()
            var modelReady by remember { mutableStateOf(false) }
            LaunchedEffect(modelReady, onboarded) {
                val done = onboarded
                if (modelReady && done != null) {
                    navController.navigate(if (done) Routes.SESSIONS else Routes.ONBOARDING) {
                        popUpTo(Routes.GATE) { inclusive = true }
                    }
                }
            }
            ModelGateScreen(onReady = { modelReady = true })
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                navController.navigate(Routes.SESSIONS) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
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
                onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ROUTINES) {
            RoutinesScreen(onBack = { navController.popBackStack() })
        }
    }
}
