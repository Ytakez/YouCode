package com.opencode.ai.viewmodel

import com.opencode.ai.data.MsgPart
import com.opencode.ai.data.OcAsk
import org.json.JSONArray
import org.json.JSONObject

/** optString даёт "null" для JSON null и "" для пустого; здесь в обоих случаях null. */
internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

internal fun parseAsk(o: JSONObject) = OcAsk(
    id = o.str("id") ?: "",
    session = o.str("sessionID") ?: "",
    action = o.str("action") ?: "",
    resources = o.optJSONArray("resources").strings(),
    message = o.str("message")
)

/** Одно сообщение ассистента: его id нужен, чтобы наложить на него потоковые дельты. */
internal class AssistantMsg(val id: String, val parts: List<MsgPart>, val done: Boolean)

/** Всё, что произошло после последнего сообщения пользователя. */
internal class OcTurn(
    val started: Boolean,
    val messages: List<AssistantMsg>,
    val error: String?,
    val idle: Boolean,
    val tokensIn: Long,
    val tokensOut: Long
) {
    val parts: List<MsgPart> get() = messages.flatMap { it.parts }
    val text: String
        get() = parts.filterIsInstance<MsgPart.Text>().joinToString("\n\n") { it.text.trim() }.trim()
    val hasText: Boolean get() = text.isNotBlank()
    val toolRunning: Boolean
        get() = parts.any { it is MsgPart.Tool && it.status != "completed" && it.status != "error" }

    /** Меняется, пока модель что-то делает: по нему видно, что ход «завис». */
    val signature: String
        get() = parts.joinToString("|") {
            when (it) {
                is MsgPart.Text -> "t${it.text.length}"
                is MsgPart.Reasoning -> "r${it.text.length}"
                is MsgPart.Tool -> "k${it.status}${it.output.length}"
            }
        } + "/" + messages.count { it.done }
}

/** API отдаёт сообщения от новых к старым; на всякий случай определяем порядок по id. */
private fun oldestFirst(raw: List<JSONObject>): List<JSONObject> {
    if (raw.size < 2) return raw
    val first = raw.first().optString("id")
    val last = raw.last().optString("id")
    return if (first > last) raw.reversed() else raw
}

private fun errorOf(o: JSONObject?): String? = o?.let { it.str("message") ?: it.str("type") ?: it.str("name") }

internal fun lastUserId(listing: JSONObject): String? =
    oldestFirst(listing.optJSONArray("data").objects()).lastOrNull { it.optString("type") == "user" }?.str("id")

/**
 * Ход после последнего сообщения пользователя. previousUserId — последнее сообщение пользователя
 * ДО отправки запроса: пока оно же в конце списка, наш запрос ещё не записан и ход не начался
 * (иначе мы приняли бы за ответ конец прошлого хода).
 */
internal fun parseTurn(listing: JSONObject, previousUserId: String?): OcTurn {
    val all = oldestFirst(listing.optJSONArray("data").objects())
    val userAt = all.indexOfLast { it.optString("type") == "user" }
    val started = userAt >= 0 && all[userAt].str("id") != previousUserId
    if (!started) return OcTurn(false, emptyList(), null, false, 0, 0)

    val turn = all.drop(userAt + 1)
    val messages = mutableListOf<AssistantMsg>()
    var error: String? = null
    var idle = false
    var tin = 0L
    var tout = 0L
    for (m in turn) {
        when (m.optString("type")) {
            "assistant" -> {
                val parts = m.optJSONArray("content").objects().mapNotNull { c ->
                    when (c.optString("type")) {
                        "text" -> MsgPart.Text(c.optString("text"))
                        "reasoning" -> {
                            val t = c.optJSONObject("time")
                            val ms = if (t != null && t.has("completed")) t.optLong("completed") - t.optLong("created") else 0L
                            MsgPart.Reasoning(c.optString("text"), ms)
                        }
                        "tool" -> {
                            val state = c.optJSONObject("state") ?: JSONObject()
                            val input = state.optJSONObject("input") ?: JSONObject()
                            val out = state.optJSONArray("content").objects().mapNotNull { it.str("text") }.joinToString("\n")
                            val arg = input.str("command") ?: input.str("filePath")?.substringAfterLast('/')
                                ?: input.str("path")?.substringAfterLast('/') ?: input.str("pattern")
                                ?: input.str("url") ?: input.str("query") ?: input.str("description")
                            MsgPart.Tool(
                                name = c.str("name") ?: "tool",
                                status = state.str("status") ?: "running",
                                arg = arg,
                                input = if (input.length() > 0) input.toString(2).take(4000) else "",
                                output = out.take(6000),
                                error = errorOf(state.optJSONObject("error"))
                            )
                        }
                        else -> null
                    }
                }
                messages += AssistantMsg(m.optString("id"), parts, m.optJSONObject("time")?.has("completed") == true)
                // Ошибка модели считается окончательной, только если OpenCode не собирается повторять запрос.
                error = if (m.optJSONObject("retry") != null) null else errorOf(m.optJSONObject("error")) ?: error
                m.optJSONObject("tokens")?.let {
                    tin += it.optLong("input")
                    tout += it.optLong("output") + it.optLong("reasoning")
                }
            }
            "idle" -> {
                idle = true
                errorOf(m.optJSONObject("error"))?.let { error = it }
            }
        }
    }
    return OcTurn(true, messages, error, idle, tin, tout)
}

/** Ответ старых движков: { parts: [{type:"text", text}] } или то же внутри data. */
internal fun legacyText(payload: JSONObject): String {
    val parts = payload.optJSONArray("parts") ?: payload.optJSONObject("data")?.optJSONArray("parts") ?: return ""
    return parts.objects().filter { it.optString("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n").trim()
}
