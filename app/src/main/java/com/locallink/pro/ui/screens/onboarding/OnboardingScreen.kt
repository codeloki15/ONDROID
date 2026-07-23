package com.locallink.pro.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.locallink.pro.ui.components.GhostPill
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.theme.*

/**
 * First-run wizard: AI key → microphone → Composio (optional) → accessibility (optional).
 * Every step is skippable — the goal is a configured app, not a hostage negotiation.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    // Live checks: mic permission + accessibility state refresh whenever we return.
    LifecycleResumeEffect(Unit) {
        vm.setMicGranted(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        vm.refreshA11y()
        onPauseOrDispose { }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.setMicGranted(granted); if (granted) vm.next() }

    fun finish() { vm.finish(); onDone() }

    AuroraBackground(glow = 0.6f) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            GradientOrb(size = 64.dp, glow = true)
            Spacer(Modifier.height(14.dp))
            Text("Set up OmniPro", style = MaterialTheme.typography.headlineMedium, color = OmniText)
            Spacer(Modifier.height(6.dp))
            StepDots(current = ui.step)
            Spacer(Modifier.height(26.dp))

            AnimatedContent(targetState = ui.step, label = "step") { step ->
                when (step) {
                    0 -> StepCard(
                        icon = Icons.Outlined.Key,
                        title = "Connect your AI brain",
                        body = "OmniPro thinks with a cloud model through your own OpenRouter key. " +
                            "Free-tier models work.",
                    ) {
                        OutlinedTextField(
                            value = ui.openRouterKey,
                            onValueChange = vm::setOpenRouterKey,
                            label = { Text("OpenRouter API key") },
                            placeholder = { Text("sk-or-v1-…", color = OmniTextFaint) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(14.dp),
                            colors = onboardingFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LinkRow("Get a free key at openrouter.ai/keys", "https://openrouter.ai/keys")
                        Spacer(Modifier.height(16.dp))
                        GradientPill(
                            "Continue", onClick = vm::next,
                            enabled = ui.openRouterKey.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SkipRow("I'll add it later") { vm.next() }
                    }

                    1 -> StepCard(
                        icon = Icons.Outlined.Mic,
                        title = "Let Omni hear you",
                        body = "Voice chat and the \"Hey Omni\" wake word need the microphone. " +
                            "Speech-to-text runs on this phone — audio never leaves the device.",
                    ) {
                        if (ui.micGranted) GrantedRow("Microphone granted")
                        Spacer(Modifier.height(16.dp))
                        if (ui.micGranted) {
                            GradientPill("Continue", onClick = vm::next, modifier = Modifier.fillMaxWidth())
                        } else {
                            GradientPill(
                                "Allow microphone",
                                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SkipRow("Skip — text only for now") { vm.next() }
                        }
                    }

                    2 -> StepCard(
                        icon = Icons.Outlined.Apps,
                        title = "Cloud app tools (optional)",
                        body = "With a free Composio key, chat and voice can search the web, read Gmail, " +
                            "post to Slack and more. You can also do this anytime in Settings.",
                    ) {
                        OutlinedTextField(
                            value = ui.composioKey,
                            onValueChange = vm::setComposioKey,
                            label = { Text("Composio API key") },
                            placeholder = { Text("ak_…", color = OmniTextFaint) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(14.dp),
                            colors = onboardingFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LinkRow("Get a key at app.composio.dev", "https://app.composio.dev")
                        Spacer(Modifier.height(16.dp))
                        GradientPill(
                            if (ui.composioKey.isBlank()) "Skip for now" else "Continue",
                            onClick = vm::next, modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    else -> StepCard(
                        icon = Icons.Outlined.Accessibility,
                        title = "Let Omni drive your phone (optional)",
                        body = "\"Automate my phone\" reads the screen and taps for you — only while " +
                            "a task is running, always with a STOP button. Android requires turning " +
                            "on the OmniPro accessibility service.",
                    ) {
                        if (ui.a11yEnabled) GrantedRow("Accessibility enabled")
                        Spacer(Modifier.height(16.dp))
                        if (ui.a11yEnabled) {
                            GradientPill("Finish", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
                        } else {
                            GradientPill(
                                "Open accessibility settings",
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Find OmniPro under Downloaded/Installed apps and switch it on, then come back.",
                                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            SkipRow("Skip — chat and voice only") { finish() }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            if (ui.step > 0) {
                GhostPill("Back", onClick = vm::back)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ─── building blocks ─────────────────────────────────────────────────────

@Composable
private fun StepCard(
    icon: ImageVector,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(OmniSurface.copy(alpha = 0.92f))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(PastelLavender),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = OmniText, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = OmniText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = OmniTextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun StepDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(4) { i ->
            Box(
                Modifier
                    .size(if (i == current) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (i == current) AuroraViolet else OmniText.copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
private fun GrantedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(OmniSuccess),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OmniSuccess)
    }
}

@Composable
private fun SkipRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = OmniTextFaint,
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun LinkRow(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(top = 10.dp, bottom = 2.dp, start = 2.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(14.dp), tint = OmniAccent)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = OmniAccent)
    }
}

@Composable
private fun onboardingFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OmniBorderFocus, unfocusedBorderColor = OmniBorder,
    focusedContainerColor = OmniSurface2, unfocusedContainerColor = OmniSurface2,
    focusedTextColor = OmniText, unfocusedTextColor = OmniText,
    focusedLabelColor = OmniAccent, unfocusedLabelColor = OmniTextFaint,
    cursorColor = OmniAccent,
)
