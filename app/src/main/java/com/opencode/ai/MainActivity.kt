package com.opencode.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.opencode.ai.auth.AuthManager
import com.opencode.ai.auth.AuthScreen
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opencode.ai.ui.screens.MainScreen
import com.opencode.ai.ui.theme.YouCodeTheme
import com.opencode.ai.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YouCodeTheme {
                val context = LocalContext.current
                val auth = remember { AuthManager(context.applicationContext) }
                var session by remember { mutableStateOf(auth.currentSession()) }
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (session == null) {
                        AuthScreen(auth = auth, onAuthenticated = { session = it })
                    } else {
                        val viewModel: ChatViewModel = viewModel()
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
