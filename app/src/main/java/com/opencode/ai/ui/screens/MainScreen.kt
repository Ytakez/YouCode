package com.opencode.ai.ui.screens

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opencode.ai.ui.components.AppDrawer
import com.opencode.ai.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

enum class Screen {
    CHAT, PROVIDERS, LOCAL_MODELS, SKILLS_MCP, SETTINGS
}

@Composable
fun MainScreen(viewModel: ChatViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.CHAT) }
    var showModelPicker by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                sessions = viewModel.sessions,
                onNewChat = {
                    viewModel.newChat()
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.CHAT
                },
                onSessionClick = { session ->
                    viewModel.loadSession(session)
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.CHAT
                },
                onProvidersClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.PROVIDERS
                },
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.SETTINGS
                },
                onModelsClick = {
                    scope.launch { drawerState.close() }
                    showModelPicker = true
                },
                onSkillsClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.SKILLS_MCP
                },
                onLocalModelsClick = {
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.LOCAL_MODELS
                }
            )
        },
        gesturesEnabled = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    (slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)
                    ) { it / 12 } + fadeIn()) togetherWith
                        (slideOutHorizontally(
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)
                        ) { -it / 18 } + fadeOut()) using SizeTransform(clip = false)
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    Screen.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onModelClick = { showModelPicker = true }
                    )
                    Screen.PROVIDERS -> ProvidersScreen(
                        onBack = { currentScreen = Screen.CHAT },
                        onLocalModelsClick = { currentScreen = Screen.LOCAL_MODELS },
                        onSettingsClick = { currentScreen = Screen.SETTINGS }
                    )
                    Screen.LOCAL_MODELS -> LocalModelsScreen(onBack = { currentScreen = Screen.PROVIDERS })
                    Screen.SKILLS_MCP -> SkillsMcpScreen(onBack = { currentScreen = Screen.CHAT })
                    Screen.SETTINGS -> SettingsScreen(onBack = { currentScreen = Screen.CHAT }, viewModel = viewModel)
                }
            }

            AnimatedVisibility(
                visible = showModelPicker,
                enter = fadeIn() + slideInHorizontally(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
                ) { it / 3 },
                exit = fadeOut() + slideOutHorizontally(
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f)
                ) { it / 4 }
            ) {
                ModelPickerScreen(
                    viewModel = viewModel,
                    onBack = { showModelPicker = false },
                    onModelSelected = {
                        viewModel.selectModel(it)
                        showModelPicker = false
                    }
                )
            }
        }
    }
}
