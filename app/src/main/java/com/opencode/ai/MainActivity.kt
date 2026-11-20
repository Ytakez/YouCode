package com.opencode.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.ai.auth.AuthManager
import com.opencode.ai.auth.AuthScreen
import com.opencode.ai.ui.screens.MainScreen
import com.opencode.ai.ui.theme.YouCodeTheme
import com.opencode.ai.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            val context = LocalContext.current
            val auth = remember { AuthManager(context.applicationContext) }
            var session by remember { mutableStateOf(auth.currentSession()) }
            LaunchedEffect(Unit) {
                if (session != null) session = auth.refreshSession()
            }
            val uiPrefs = remember {
                context.getSharedPreferences("youcode_ui", android.content.Context.MODE_PRIVATE)
            }
            val loggedOutTheme = uiPrefs.getString("theme", "system") ?: "system"
            val loggedOutDynamic = uiPrefs.getBoolean("dynamic", true)

            if (session == null) {
                YouCodeTheme(themeMode = loggedOutTheme, dynamicColor = loggedOutDynamic) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AuthScreen(auth = auth, onAuthenticated = { session = it })
                    }
                }
            } else {
                val viewModel: ChatViewModel = viewModel()
                YouCodeTheme(
                    themeMode = viewModel.themeMode,
                    dynamicColor = viewModel.dynamicColor
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            viewModel = viewModel,
                            onSignOut = {
                                viewModel.wipeLocalData()
                                auth.signOut()
                                this@MainActivity.viewModelStore.clear()
                                session = null
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
