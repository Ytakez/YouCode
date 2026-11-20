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
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L

private val ScreenBg = Color(0xFF0A0A0B)
private val SoftWhite = Color(0xFFF5F5F7)
private val Muted = Color(0xFF9A9AA0)
/** Светло-розоватый белый, как у контура в «Welcome to your Pixel». */
private val RingColor = Color(0xFFF4DDE4)

/** Сколько волн на кольце. */
private const val RING_LOBES = 16

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
        // Волнистый контур прыгает на растянутой дуге, как на Welcome to your Pixel
        PixelBlob(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .align(Alignment.TopCenter),
            active = waiting || busy
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(330.dp))

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
                "Несколько ИИ-чатов на одном устройстве.\nВход через Telegram — быстро и без пароля.",
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
                            var serverErrors = 0
                            while (session == null && System.currentTimeMillis() < deadline) {
                                delay(2000)
                                try {
                                    session = auth.pollTelegramLogin(login.token)
                                    serverErrors = 0
                                } catch (e: AuthHttpException) {
                                    // Временный сбой сервера (холодный старт функции и т.п.): запрос на сервере
                                    // сохраняется, поэтому пробуем ещё. Подряд 5 ошибок — показываем её.
                                    if (e.code in 500..599 && ++serverErrors < 5) continue
                                    throw e
                                }
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
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.lerp(
                        SoftWhite, MaterialTheme.colorScheme.primaryContainer, 0.5f
                    ),
                    contentColor = ScreenBg,
                    disabledContainerColor = SoftWhite.copy(alpha = 0.55f),
                    disabledContentColor = ScreenBg.copy(alpha = 0.7f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
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
 * Как на экране Welcome to your Pixel: волнистый контур-«змейка» (светлая дуга бежит по кругу,
 * по контуру бегут волны) прыгает на тонкой растянутой дуге внизу. При приземлении контур
 * сплющивается, а дуга чуть прогибается под ним.
 */
@Composable
private fun PixelBlob(modifier: Modifier = Modifier, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "wave_ring")

    // «Змейка»: светлая дуга бежит по кругу, длина дуги дышит (иногда контур замыкается полностью).
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 2600 else 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit"
    )
    val sweep by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )
    // Волны на контуре сами бегут по кругу.
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "travel"
    )
    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "morph"
    )
    // Фаза прыжка: 0 — контур стоит на дуге, 0.5 — верхняя точка, 1 — снова приземлился.
    val hop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 1100 else 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hop"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val full = 2f * PI.toFloat()

        // ── Растянутая дуга внизу: верх очень большого круга, уходит за края и тает ──
        val arcTop = size.height * 0.86f
        val bigR = w * 1.4f
        val half = w / 2f

        // Прыжок: парабола + сплющивание в момент касания.
        val ringR = min(w * 0.28f, 105.dp.toPx())
        val jumpH = ringR * 0.38f
        val air = 4f * hop * (1f - hop)                       // 0 на дуге, 1 в верхней точке
        val squash = max(0f, 1f - hop / 0.14f) + max(0f, (hop - 0.86f) / 0.14f)
        val sy = (1f + 0.05f * air) * (1f - 0.14f * squash)
        val sx = (1f - 0.03f * air) * (1f + 0.10f * squash)
        val dentMax = 5.dp.toPx() * squash

        val cx = half
        val arcStroke = 1.5.dp.toPx()
        val arcColor = Color(0xFFCFD8E8)
        val arcSteps = 90
        var prevArc: Offset? = null
        for (i in 0..arcSteps) {
            val x = w * i / arcSteps.toFloat()
            val dx = x - half
            val curve = bigR - sqrt(bigR * bigR - dx * dx)
            val dent = dentMax * exp(-(dx * dx) / (2f * (ringR * 0.45f) * (ringR * 0.45f)))
            val pt = Offset(x, arcTop + curve + dent)
            val last = prevArc
            if (last != null) {
                val u = (abs(dx) / half).coerceIn(0f, 1f)
                val alpha = 0.18f + 0.62f * (1f - u * u)
                drawLine(arcColor.copy(alpha = alpha), last, pt, arcStroke, StrokeCap.Butt)
            }
            prevArc = pt
        }

        // ── Волнистый контур ──
        val depth = 0.035f + 0.02f * morph
        val cy = arcTop + dentMax * 0.6f - ringR * sy - jumpH * air
        val startAngle = orbit * full - PI.toFloat() / 2f
        val length = sweep * full
        val steps = 200
        val lineW = 2.dp.toPx()
        val ringColor = RingColor

        var prev: Offset? = null
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = startAngle + f * length
            val r = ringR * (1f + depth * sin(RING_LOBES * a - travel))
            val point = Offset(cx + r * cos(a) * sx, cy + r * sin(a) * sy)
            val last = prev
            if (last != null) {
                // дуга светится почти ровно, только самый хвост слегка тает
                val alpha = (0.3f + 0.7f * f).coerceIn(0f, 1f)
                drawLine(ringColor.copy(alpha = alpha * 0.16f), last, point, lineW * 4f, StrokeCap.Butt)
                drawLine(ringColor.copy(alpha = alpha), last, point, lineW, StrokeCap.Butt)
            }
            prev = point
        }
        prev?.let { head ->
            drawCircle(ringColor.copy(alpha = 0.25f), radius = lineW * 3f, center = head)
            drawCircle(ringColor, radius = lineW * 0.9f, center = head)
        }
    }
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
