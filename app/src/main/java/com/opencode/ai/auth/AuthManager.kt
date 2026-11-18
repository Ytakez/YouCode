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

    suspend fun signUp(email: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val json = request("/auth/v1/signup", JSONObject().put("email", email).put("password", password))
        if (json.has("access_token")) {
            sessionFrom(json).also(::save)
            true
        } else false
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
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (c.responseCode !in 200..299) {
            val message = runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") }.ifBlank { JSONObject(text).optString("error_description") } }.getOrDefault("")
            throw IllegalStateException(message.ifBlank { "Ошибка Supabase (${c.responseCode})" })
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
