package com.opencode.ai.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.opencode.ai.BuildConfig
import com.opencode.ai.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Профиль пользователя.
 * [callName]: null — ИИ зовёт по [displayName]; пустая строка — ИИ не называет по имени; иначе — своё обращение.
 */
data class UserProfile(
    val username: String = "",
    val displayName: String = "",
    val callName: String? = null,
    val avatarUrl: String = "",
    val onboarded: Boolean = false
) {
    /** Как ИИ должен обращаться к человеку (пусто — без имени). */
    val addressAs: String get() = callName ?: displayName
}

enum class ProfileStatus { LOADING, READY, ERROR }

/** Профиль в Supabase (таблица profiles + корзина avatars) с кэшем на устройстве. */
class ProfileStore(
    context: Context,
    private val auth: AuthManager,
    private val userId: String
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("youcode_profile", Context.MODE_PRIVATE)
    private val base = BuildConfig.SUPABASE_URL
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    private val cached: UserProfile? = prefs.getString(cacheKey(), null)?.let { raw ->
        runCatching { fromJson(JSONObject(raw)) }.getOrNull()
    }

    val hasCache: Boolean get() = cached != null

    var profile by mutableStateOf(cached ?: UserProfile())
        private set

    var status by mutableStateOf(if (cached != null) ProfileStatus.READY else ProfileStatus.LOADING)
        private set

    /** Нужно ли показать знакомство: только когда сервер ответил и подтвердил, что оно ещё не пройдено. */
    val needsOnboarding: Boolean get() = status == ProfileStatus.READY && !profile.onboarded

    private fun cacheKey() = "profile_$userId"

    suspend fun refresh() {
        val result = runCatching {
            val (code, text) = call(
                "GET",
                "/rest/v1/profiles?id=eq.$userId&select=username,display_name,call_name,avatar_url,onboarded"
            )
            check(code in 200..299) { "HTTP $code" }
            val arr = org.json.JSONArray(text)
            check(arr.length() > 0) { "profile not found" }
            fromJson(arr.getJSONObject(0))
        }
        result.onSuccess { applyServer(it) }
        if (result.isFailure && status == ProfileStatus.LOADING) status = ProfileStatus.ERROR
    }

    /** Меняет поля профиля. Бросает исключение, если сервер не принял изменение. */
    suspend fun update(
        displayName: String? = null,
        callName: String? = null,
        clearCallName: Boolean = false,
        avatarUrl: String? = null,
        onboarded: Boolean? = null
    ) {
        val patch = JSONObject()
        displayName?.let { patch.put("display_name", it.trim()) }
        if (clearCallName) patch.put("call_name", JSONObject.NULL) else callName?.let { patch.put("call_name", it.trim()) }
        avatarUrl?.let { patch.put("avatar_url", it) }
        onboarded?.let { patch.put("onboarded", it) }
        val (code, text) = call("POST", "/rest/v1/rpc/update_my_profile", JSONObject().put("p_patch", patch).toString().toByteArray())
        if (code !in 200..299) throw IllegalStateException(errorText(code, text))
        applyServer(fromJson(JSONObject(text)).let { it.copy(username = it.username.ifBlank { profile.username }) })
    }

    /** Загружает фото профиля и записывает его адрес в профиль. */
    suspend fun uploadAvatar(uri: Uri) {
        val jpeg = withContext(Dispatchers.IO) { squareJpeg(uri) }
        val path = "$userId/avatar.jpg"
        val (code, text) = call(
            "POST", "/storage/v1/object/avatars/$path", jpeg,
            contentType = "image/jpeg", extra = mapOf("x-upsert" to "true")
        )
        if (code !in 200..299) throw IllegalStateException(errorText(code, text))
        // ?v= сбивает кэш: адрес файла всегда один и тот же
        update(avatarUrl = "$base/storage/v1/object/public/avatars/$path?v=${System.currentTimeMillis()}")
    }

    fun clear() {
        prefs.edit().remove(cacheKey()).apply()
    }

    private fun applyServer(p: UserProfile) {
        profile = p
        status = ProfileStatus.READY
        prefs.edit().putString(cacheKey(), toJson(p).toString()).apply()
    }

    private fun toJson(p: UserProfile) = JSONObject()
        .put("username", p.username)
        .put("display_name", p.displayName)
        .put("call_name", p.callName ?: JSONObject.NULL)
        .put("avatar_url", p.avatarUrl)
        .put("onboarded", p.onboarded)

    private fun fromJson(o: JSONObject) = UserProfile(
        username = o.optString("username").takeUnless { o.isNull("username") }.orEmpty(),
        displayName = o.optString("display_name").takeUnless { o.isNull("display_name") }.orEmpty(),
        callName = if (o.isNull("call_name") || !o.has("call_name")) null else o.optString("call_name"),
        avatarUrl = o.optString("avatar_url").takeUnless { o.isNull("avatar_url") }.orEmpty(),
        onboarded = o.optBoolean("onboarded", false)
    )

    private fun errorText(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text) }.getOrNull()?.let {
            it.optString("message").ifBlank { it.optString("error") }
        }.orEmpty()
        return "Не удалось сохранить ($code)${if (msg.isNotBlank()) ": $msg" else ""}"
    }

    /** Запрос к Supabase с токеном пользователя; при 401 один раз обновляет сессию. */
    private suspend fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        extra: Map<String, String> = emptyMap()
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val token = auth.currentSession()?.accessToken ?: throw IllegalStateException("Нет активной сессии")
        val first = rawRequest(token, method, path, body, contentType, extra)
        if (first.first != 401) {
            first
        } else {
            val fresh = auth.refreshSession()?.accessToken
            if (fresh == null) first else rawRequest(fresh, method, path, body, contentType, extra)
        }
    }

    private fun rawRequest(
        token: String,
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String,
        extra: Map<String, String>
    ): Pair<Int, String> {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            extra.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", contentType) }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** Центрированный квадрат 512×512, JPEG. */
    private fun squareJpeg(uri: Uri): ByteArray {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("Не удалось открыть изображение")
        // Фото с камеры хранятся «лёжа» с пометкой поворота в EXIF: без неё аватарка получалась повёрнутой.
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val upright = if (degrees == 0f) bmp else Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(degrees) }, true
        )
        val side = minOf(upright.width, upright.height)
        val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 512, 512, true)
        // JPEG не умеет прозрачность: подкладываем белый фон, иначе PNG с прозрачным фоном становится чёрным.
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }
}
