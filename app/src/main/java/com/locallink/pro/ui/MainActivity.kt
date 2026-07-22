package com.locallink.pro.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.pilot.PilotProjectionHolder
import com.locallink.pro.service.pilot.PilotProjectionRequest
import com.locallink.pro.service.pilot.PilotProjectionService
import com.locallink.pro.service.voice.VoiceLoopService
import com.locallink.pro.ui.navigation.LocalLinkNavGraph
import com.locallink.pro.ui.theme.LocalLinkProTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions granted or denied — UI will react via state
    }

    // Screen-capture consent for Omni Pilot vision. On grant, start the projection service.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            PilotProjectionService.start(this, result.resultCode, data)
        }
        PilotProjectionRequest.consumed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        // Pilot asks (via this flow) for screen-capture consent when a run needs vision and none
        // is active yet. We prompt once; the projection then persists for the session.
        lifecycleScope.launch {
            PilotProjectionRequest.requests.collect {
                if (!PilotProjectionHolder.isReady) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    runCatching { projectionLauncher.launch(mpm.createScreenCaptureIntent()) }
                }
            }
        }

        // Resume hands-free listening if the user left it on (Activity is visible → FGS allowed).
        lifecycleScope.launch {
            if (settings.loadHandsFree() &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                VoiceLoopService.start(this@MainActivity)
            }
        }

        setContent {
            LocalLinkProTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    LocalLinkNavGraph(navController = navController)
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            // On-device tool permissions (calendar/contacts read, phone calls)
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
