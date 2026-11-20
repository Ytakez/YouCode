package com.opencode.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.ai.auth.AuthManager
import com.opencode.ai.auth.AuthScreen
import com.opencode.ai.auth.AuthSession
import com.opencode.ai.profile.OnboardingScreen
import com.opencode.ai.profile.ProfileStatus
import com.opencode.ai.profile.ProfileStore
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

            val current: AuthSession? = session
            if (current == null) {
                YouCodeTheme(themeMode = loggedOutTheme, dynamicColor = loggedOutDynamic) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AuthScreen(auth = auth, onAuthenticated = { session = it })
                    }
                }
            } else {
                val viewModel: ChatViewModel = viewModel()
                val profileStore = remember(current.userId) {
                    ProfileStore(context.applicationContext, auth, current.userId)
                }
                LaunchedEffect(profileStore) { profileStore.refresh() }
                // ИИ узнаёт, как обращаться к человеку (имя меняется в профиле и действует сразу)
                LaunchedEffect(profileStore.profile.displayName, profileStore.profile.callName) {
                    viewModel.setUserNames(profileStore.profile.displayName, profileStore.profile.callName)
                }
                YouCodeTheme(
                    themeMode = viewModel.themeMode,
                    dynamicColor = viewModel.dynamicColor
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when {
                            profileStore.status == ProfileStatus.LOADING ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            profileStore.needsOnboarding -> OnboardingScreen(store = profileStore)
                            else -> MainScreen(
                                viewModel = viewModel,
                                profile = profileStore,
                                onSignOut = {
                                    viewModel.wipeLocalData()
                                    profileStore.clear()
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
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
