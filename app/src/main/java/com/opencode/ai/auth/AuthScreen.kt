package com.opencode.ai.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L

private val ScreenBg = Color(0xFF0A0A0B)
private val SoftWhite = Color(0xFFF5F5F7)
private val Muted = Color(0xFF9A9AA0)
private val BlobStroke = Color(0xFFE8E8EC)
private val BlobGlow = Color(0x33FFFFFF)

@Composable
fun AuthScreen(onAuthenticated: (AuthSession) -> Unit, auth: AuthManager) {
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Анимированная «облачная» фигура как на Welcome to your Pixel
        PixelBlob(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .align(Alignment.TopCenter)
                .padding(top = 36.dp),
            active = waiting || busy
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(280.dp))

            Text(
                "Добро пожаловать\nв YouCode",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.3).sp
                ),
                color = SoftWhite,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            Text(
                "Премиум-чат с ИИ на вашем телефоне.\nВход через Telegram — быстро и без паролей.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                ),
                color = Muted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f, fill = true))
            Spacer(Modifier.height(24.dp))

            AnimatedContent(
                targetState = when {
                    message != null -> "error"
                    waiting -> "waiting"
                    else -> "idle"
                },
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(160))
                },
                label = "auth_status"
            ) { state ->
                when (state) {
                    "waiting" -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 18.dp)
                        ) {
                            WaitingDots()
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Откройте Telegram и подтвердите вход в боте.\nПотом вернитесь сюда — вход завершится сам.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    "error" -> {
                        Text(
                            message.orEmpty(),
                            color = Color(0xFFFF8A80),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 18.dp)
                        )
                    }
                    else -> Spacer(Modifier.height(8.dp))
                }
            }

            Button(
                onClick = {
                    message = null
                    busy = true
                    loginJob = scope.launch {
                        try {
                            val login = auth.startTelegramLogin()
                            waiting = true
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(login.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                            var session: AuthSession? = null
                            while (session == null && System.currentTimeMillis() < deadline) {
                                delay(2000)
                                session = auth.pollTelegramLogin(login.token)
                            }
                            if (session != null) onAuthenticated(session)
                            else message = "Время ожидания вышло. Попробуйте ещё раз."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = e.message ?: "Не удалось выполнить запрос"
                        } finally {
                            busy = false
                            waiting = false
                        }
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoftWhite,
                    contentColor = ScreenBg,
                    disabledContainerColor = SoftWhite.copy(alpha = 0.55f),
                    disabledContentColor = ScreenBg.copy(alpha = 0.7f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (busy && !waiting) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = ScreenBg
                    )
                } else {
                    Text(
                        if (waiting) "Ожидаем подтверждение…" else "Войти через Telegram",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                    )
                }
            }

            AnimatedVisibility(visible = busy) {
                TextButton(
                    onClick = {
                        loginJob?.cancel()
                        busy = false
                        waiting = false
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Отмена", color = Muted)
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * Мягкая «облачная» фигура как на экране Welcome to your Pixel:
 * волнистый контур, лёгкое свечение, плавное «прыгание» и морфинг.
 */
@Composable
private fun PixelBlob(modifier: Modifier = Modifier, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "blob")

    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "morph"
    )
    val bounce by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 14000 else 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )
    val breath by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (active) 1.08f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f - 8.dp.toPx() + bounce * 14.dp.toPx()
        val baseR = size.minDimension * 0.28f

        // Мягкое свечение под фигурой
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BlobGlow, Color.Transparent),
                center = Offset(cx, cy),
                radius = baseR * 1.85f * breath
            ),
            radius = baseR * 1.85f * breath,
            center = Offset(cx, cy)
        )

        scale(breath, Offset(cx, cy)) {
            rotate(spin * 0.08f, Offset(cx, cy)) {
                val path = scallopedPath(
                    center = Offset(cx, cy),
                    radius = baseR,
                    lobes = 10,
                    depth = 0.12f + morph * 0.07f,
                    phase = morph * PI.toFloat() * 2f
                )
                drawPath(
                    path = path,
                    color = BlobStroke.copy(alpha = 0.85f),
                    style = Stroke(width = 1.6.dp.toPx())
                )
                val inner = scallopedPath(
                    center = Offset(cx, cy),
                    radius = baseR * 0.72f,
                    lobes = 10,
                    depth = 0.08f + morph * 0.04f,
                    phase = morph * PI.toFloat() * 2f + 0.4f
                )
                drawPath(
                    path = inner,
                    color = BlobStroke.copy(alpha = 0.22f),
                    style = Stroke(width = 1.1.dp.toPx())
                )
            }
        }
    }
}

/** Волнистый «облачный» контур (scalloped circle). */
private fun scallopedPath(
    center: Offset,
    radius: Float,
    lobes: Int,
    depth: Float,
    phase: Float
): Path {
    val path = Path()
    val steps = lobes * 24
    for (i in 0..steps) {
        val t = i / steps.toFloat() * 2f * PI.toFloat()
        val wave = sin(t * lobes + phase)
        val r = radius * (1f + depth * wave)
        val x = center.x + r * cos(t)
        val y = center.y + r * sin(t)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Composable
private fun WaitingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(480, delayMillis = i * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Canvas(Modifier.size(7.dp)) {
                drawCircle(SoftWhite.copy(alpha = alpha))
            }
        }
    }
}
