package com.opencode.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: ChatViewModel = viewModel()
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
