package com.opencode.ai.auth

import com.opencode.ai.BuildConfig
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuthSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String)

data class TelegramLogin(val token: String, val url: String)

/** Ответ Supabase с ошибкой: по коду отличаем «сессия отклонена» от «нет сети». */
class AuthHttpException(val code: Int, message: String) : IllegalStateException(message)

class AuthManager(private val context: Context) {
    companion object {
        private const val PREFS = "youcode_auth"
        private const val TOKEN = "session"
        private const val KEY_ALIAS = "youcode_auth_key"
        private val BASE = BuildConfig.SUPABASE_URL
        private val API_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    }

    /** Ссылка на бота: Telegram откроется на стороне пользователя, а токен нужен для опроса результата. */
    suspend fun startTelegramLogin(): TelegramLogin = withContext(Dispatchers.IO) {
        val (code, json) = functionRequest(JSONObject().put("action", "start"))
        // Edge Function может вернуть url или link (в проде сейчас link).
        val token = json.optString("token")
        val url = json.optString("url").ifBlank { json.optString("link") }
        if (code !in 200..299) {
            val detail = json.optString("error").ifBlank { json.optString("message") }
            throw AuthHttpException(code, "Не удалось начать вход через Telegram ($code)${if (detail.isNotBlank()) ": $detail" else ""}")
        }
        if (token.isBlank() || url.isBlank()) {
            throw AuthHttpException(
                code,
                "Не удалось начать вход через Telegram: в ответе нет token/url (${json.toString().take(200)})"
            )
        }
        TelegramLogin(token, url)
    }

    /** null — пользователь ещё не подтвердил вход в боте (или временно нет сети). */
    suspend fun pollTelegramLogin(token: String): AuthSession? = withContext(Dispatchers.IO) {
        val (code, json) = try {
            functionRequest(JSONObject().put("action", "poll").put("token", token))
        } catch (e: java.io.IOException) {
            return@withContext null
        }
        val status = json.optString("status")
        when {
            code == 410 || status == "expired" ->
                throw AuthHttpException(410, "Ссылка для входа устарела. Попробуйте ещё раз.")
            code !in 200..299 -> {
                val detail = json.optString("error").ifBlank { json.optString("message") }
                throw AuthHttpException(code, "Ошибка входа через Telegram ($code)${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            // Ошибка сессии на стороне функции (раньше мог прийти 200 без user)
            json.has("error") && status != "ok" && status != "pending" ->
                throw AuthHttpException(code, "Ошибка входа: ${json.optString("error")}")
            status == "ok" || json.has("access_token") || json.optJSONObject("session") != null ->
                sessionFrom(json).also(::save)
            else -> null // pending
        }
    }

    private fun functionRequest(body: JSONObject): Pair<Int, JSONObject> {
        val c = (URL("$BASE/functions/v1/telegram-auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
            // Supabase Edge Functions часто требуют оба заголовка; без Authorization бывает 401.
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Authorization", "Bearer $API_KEY")
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    /**
     * Обновляет токены по refresh_token. Если Supabase отклонил сессию (400/401/403) — выходим из аккаунта и возвращаем null.
     * Нет сети или ошибка сервера — остаёмся с текущей сессией.
     */
    suspend fun refreshSession(): AuthSession? = withContext(Dispatchers.IO) {
        val current = currentSession() ?: return@withContext null
        try {
            val json = request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", current.refreshToken))
            sessionFrom(json).also(::save)
        } catch (e: AuthHttpException) {
            if (e.code == 400 || e.code == 401 || e.code == 403) { signOut(); null } else current
        } catch (e: Exception) {
            current
        }
    }

    fun currentSession(): AuthSession? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, null) ?: return null
        val plain = decrypt(Base64.decode(raw, Base64.NO_WRAP))
        val j = JSONObject(plain)
        AuthSession(j.getString("access"), j.getString("refresh"), j.getString("id"), j.getString("email"))
    }.getOrNull()

    fun signOut() { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).apply() }

    private fun sessionFrom(j: JSONObject): AuthSession {
        // Плоские поля (наша Edge Function) или вложенный session (как у GoTrue).
        val session = j.optJSONObject("session") ?: j
        val access = session.optString("access_token").ifBlank { j.optString("access_token") }
        val refresh = session.optString("refresh_token").ifBlank { j.optString("refresh_token") }
        if (access.isBlank() || refresh.isBlank()) {
            throw IllegalStateException(
                "Supabase не вернул токены: ${j.toString().take(240)}"
            )
        }
        val user = session.optJSONObject("user")
            ?: j.optJSONObject("user")
            ?: JSONObject()
        var id = user.optString("id").ifBlank { user.optString("sub") }
        var email = user.optString("email")
        // Запасной путь: user id из JWT payload (access_token).
        if (id.isBlank()) {
            val payload = decodeJwtPayload(access)
            id = payload?.optString("sub").orEmpty()
            if (email.isBlank()) email = payload?.optString("email").orEmpty()
        }
        if (id.isBlank()) {
            throw IllegalStateException(
                "Supabase не вернул данные пользователя: ${j.toString().take(240)}"
            )
        }
        return AuthSession(access, refresh, id, email)
    }

    /** Декодирует payload JWT без проверки подписи (только чтобы достать sub/email). */
    private fun decodeJwtPayload(jwt: String): JSONObject? = runCatching {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        val raw = parts[1]
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val json = String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(json)
    }.getOrNull()

    private fun save(s: AuthSession) {
        val json = JSONObject().put("access", s.accessToken).put("refresh", s.refreshToken).put("id", s.userId).put("email", s.email).toString()
        val encrypted = encrypt(json)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Authorization", "Bearer $API_KEY")
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") }.ifBlank { JSONObject(text).optString("error_description") } }.getOrDefault("")
            throw AuthHttpException(code, message.ifBlank { "Ошибка Supabase ($code)" })
        }
        return JSONObject(text)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun decrypt(value: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        return String(cipher.doFinal(value.copyOfRange(12, value.size)), Charsets.UTF_8)
    }
}
