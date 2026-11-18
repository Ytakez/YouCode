package com.opencode.ai.auth

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

/** Ошибка Supabase Auth. [code] — поле error_code из ответа (например email_not_confirmed). */
class AuthException(val code: String, message: String) : Exception(message)

data class TelegramRequest(val token: String, val link: String)

class AuthManager(private val context: Context) {
    companion object {
        private const val PREFS = "youcode_auth"
        private const val TOKEN = "session"
        private const val KEY_ALIAS = "youcode_auth_key"
        private const val BASE = "https://gzuzyagiqgvpeqkksaml.supabase.co"
        private const val API_KEY = "sb_publishable_gXGst8Hdc4LbcdGqMF4MbA_bwAodaTr"
    }

    suspend fun signIn(email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        val json = request("/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password))
        sessionFrom(json).also(::save)
    }

    /** true — вход выполнен сразу (подтверждение почты выключено); false — на почту ушёл код, нужно вызвать [verifyEmail]. */
    suspend fun signUp(email: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val json = request("/auth/v1/signup", JSONObject().put("email", email).put("password", password))
        if (json.has("access_token")) {
            sessionFrom(json).also(::save)
            true
        } else false
    }

    /** Подтверждение почты кодом из письма. При успехе сразу сохраняет сессию. */
    suspend fun verifyEmail(email: String, code: String): AuthSession = withContext(Dispatchers.IO) {
        val json = request("/auth/v1/verify", JSONObject().put("type", "signup").put("email", email).put("token", code))
        sessionFrom(json).also(::save)
    }

    /** Отправляет код подтверждения на почту ещё раз. */
    suspend fun resendCode(email: String) {
        withContext(Dispatchers.IO) { request("/auth/v1/resend", JSONObject().put("type", "signup").put("email", email)) }
    }

    /** Вход через Telegram, шаг 1: сервер выдаёт одноразовую ссылку на бота. */
    suspend fun telegramStart(): TelegramRequest = withContext(Dispatchers.IO) {
        val j = request("/functions/v1/telegram-auth", JSONObject().put("action", "start"))
        val token = j.optString("token")
        val link = j.optString("link")
        if (token.isBlank() || link.isBlank()) throw AuthException("telegram_error", j.optString("message").ifBlank { "Сервер входа через Telegram вернул пустой ответ" })
        TelegramRequest(token, link)
    }

    /** Шаг 2: null — пользователь ещё не подтвердил вход в Telegram; иначе сессия (уже сохранена). */
    suspend fun telegramPoll(token: String): AuthSession? = withContext(Dispatchers.IO) {
        val j = request("/functions/v1/telegram-auth", JSONObject().put("action", "poll").put("token", token))
        when (j.optString("status")) {
            "ok" -> sessionFrom(j.optJSONObject("session") ?: throw AuthException("telegram_error", "Сервер не вернул сессию")).also(::save)
            "pending" -> null
            "expired" -> throw AuthException("telegram_expired", "Ссылка устарела. Нажмите «Войти через Telegram» ещё раз.")
            else -> throw AuthException("telegram_error", j.optString("message").ifBlank { "Не удалось войти через Telegram" })
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
        val user = j.optJSONObject("user") ?: throw IllegalStateException("Supabase не вернул данные пользователя")
        return AuthSession(j.getString("access_token"), j.getString("refresh_token"), user.getString("id"), user.optString("email"))
    }

    private fun save(s: AuthSession) {
        val json = JSONObject().put("access", s.accessToken).put("refresh", s.refreshToken).put("id", s.userId).put("email", s.email).toString()
        val encrypted = encrypt(json)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun request(path: String, body: JSONObject): JSONObject {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
            setRequestProperty("apikey", API_KEY); setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val ok = c.responseCode in 200..299
        val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) {
            val obj = runCatching { JSONObject(text) }.getOrNull()
            val code = obj?.optString("error_code").orEmpty()
            val raw = obj?.let { o -> o.optString("msg").ifBlank { o.optString("message") }.ifBlank { o.optString("error_description") } }.orEmpty()
            throw AuthException(code, friendly(code, raw, c.responseCode))
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun friendly(code: String, raw: String, http: Int): String = when (code) {
        "over_email_send_rate_limit" -> "Слишком много писем за короткое время. Подождите немного и попробуйте снова. Если ошибка повторяется — в Supabase не подключён свой SMTP (см. SUPABASE_SETUP.md)."
        "email_address_not_authorized" -> "Supabase не может отправить письмо на этот адрес: встроенная отправка работает только для команды проекта. Подключите свой SMTP в Supabase (см. SUPABASE_SETUP.md)."
        "email_address_invalid" -> "Некорректный адрес почты."
        "email_not_confirmed" -> "Почта ещё не подтверждена."
        "invalid_credentials" -> "Неверная почта или пароль."
        "otp_expired" -> "Неверный код или срок его действия истёк. Запросите новый код."
        "user_already_exists", "email_exists" -> "Аккаунт с такой почтой уже есть. Войдите."
        "weak_password" -> "Слишком простой пароль."
        "signup_disabled" -> "Регистрация отключена в настройках Supabase."
        "over_request_rate_limit" -> "Слишком много попыток. Подождите минуту."
        else -> when {
            http == 404 -> "Сервер входа не найден: разверните функцию telegram-auth в Supabase (см. SUPABASE_SETUP.md)."
            http == 401 -> "Supabase отклонил запрос: у функции telegram-auth выключите Verify JWT (см. SUPABASE_SETUP.md)."
            else -> raw.ifBlank { "Ошибка Supabase ($http)" }
        }
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
