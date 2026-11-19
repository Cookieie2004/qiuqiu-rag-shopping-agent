package com.example.ragshoppingagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * SSE 流式聊天客户端。
 * 负责与后端 /chat 端点通信，解析 SSE 事件并通过回调通知上层。
 *
 * 演示模式：
 * - recordMode = true 时，正常请求后端，同时把 SSE 事件录制到本地文件
 * - demoMode = true 时，不请求后端，从本地缓存文件回放，文本匀速输出
 */
class ChatClient(private val serverBaseUrl: String) {

    var demoMode = false       // 演示回放模式
    var recordMode = false     // 录制模式（正常请求+存文件）
    var context: Context? = null  // 用于访问 filesDir

    /** 每秒输出的字符数（演示模式） */
    private val DEMO_CHARS_PER_SEC = 25

    interface Listener {
        fun onText(content: String)
        fun onBubbleBreak()
        fun onProduct(product: JSONObject, rawMarker: String)
        fun onClarify(clarify: JSONObject)
        fun onDebugIntent(debug: JSONObject)
    }

    /** 把 query 转成安全的文件名 */
    private fun cacheFileName(query: String): String {
        val safe = query.trim().take(30).replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
        return "demo_${safe}.jsonl"
    }

    private fun cacheDir(): File {
        val dir = File(context?.filesDir ?: File("/tmp"), "demo_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 同步执行 SSE 流式请求（应在后台线程调用）。
     */
    fun streamChat(
        query: String,
        sessionId: String,
        history: List<JSONObject>,
        listener: Listener,
    ) {
        if (demoMode) {
            val cacheFile = File(cacheDir(), cacheFileName(query))
            if (cacheFile.exists()) {
                replayFromCache(query, listener)
                return
            }
            // 缓存未命中，静默回退到正常模式请求后端
        }

        val cacheWriter = if (recordMode) {
            File(cacheDir(), cacheFileName(query)).bufferedWriter()
        } else null

        val connection = (URL("$serverBaseUrl/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
        }

        val body = JSONObject()
            .put("query", query)
            .put("session_id", sessionId)
            .put("history", JSONArray().apply { history.forEach { put(it) } })
            .put("image_url", JSONObject.NULL)
            .toString()

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

        if (connection.responseCode !in 200..299) {
            cacheWriter?.close()
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }

        try {
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    // 录制模式：存到文件
                    cacheWriter?.appendLine(data)

                    val event = JSONObject(data)
                    dispatchEvent(event, listener)
                }
            }
        } finally {
            cacheWriter?.close()
            connection.disconnect()
        }
    }

    /** 从缓存文件回放，文本匀速逐字输出 */
    private fun replayFromCache(query: String, listener: Listener) {
        val file = File(cacheDir(), cacheFileName(query))
        if (!file.exists()) {
            return
        }

        val lines = file.readLines()
        val charDelayMs = 1000L / DEMO_CHARS_PER_SEC

        // 演示模式"思考中"延迟：根据回复类型决定等待时间
        val hasClarify = lines.any { runCatching { JSONObject(it).optString("type") }.getOrDefault("") == "clarify" }
        val totalTextLen = lines.sumOf { runCatching { val e = JSONObject(it); if (e.optString("type") == "text") e.optString("content").length else 0 }.getOrDefault(0) }
        val thinkDelay = when {
            hasClarify -> 1000L        // clarify 追问：1秒
            totalTextLen < 150 -> 2000L // 短回答：2秒
            else -> 3000L              // 长回答：3秒
        }
        Thread.sleep(thinkDelay)
        if (Thread.currentThread().isInterrupted) return

        lines.forEach { line ->
            if (Thread.currentThread().isInterrupted) return
            val event = JSONObject(line)
            val type = event.optString("type")

            if (type == "text") {
                // 文本逐字匀速输出
                val content = event.optString("content")
                for (ch in content) {
                    if (Thread.currentThread().isInterrupted) return
                    listener.onText(ch.toString())
                    Thread.sleep(charDelayMs)
                }
            } else {
                // 非文本事件：短暂停顿后直接派发
                Thread.sleep(50)
                dispatchEvent(event, listener)
            }
        }
    }

    /** 统一事件派发 */
    private fun dispatchEvent(event: JSONObject, listener: Listener) {
        when (event.optString("type")) {
            "text" -> listener.onText(event.optString("content"))
            "bubble_break" -> listener.onBubbleBreak()
            "product" -> {
                val product = event.optJSONObject("content") ?: JSONObject()
                val pid = product.optString("id", "")
                val marker = if (pid.isNotBlank()) "<!--PRODUCT: $pid-->" else ""
                listener.onProduct(product, marker)
            }
            "clarify" -> {
                val clarify = event.optJSONObject("content") ?: JSONObject()
                listener.onClarify(clarify)
            }
            "debug" -> {
                if (event.optString("event") == "intent") {
                    event.optJSONObject("content")?.let { listener.onDebugIntent(it) }
                }
            }
        }
    }

    /** 同步上传 WAV 进行 ASR 识别 */
    fun uploadAsr(wavBytes: ByteArray): String {
        val conn = (URL("$serverBaseUrl/asr").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "audio/wav")
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
        }
        conn.outputStream.use { it.write(wavBytes) }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("ASR HTTP ${conn.responseCode}")
        }
        val respText = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(respText).optString("text", "")
    }

    /** 同步获取商品详情 */
    fun fetchProductDetail(productId: String): JSONObject {
        val connection = (URL("$serverBaseUrl/products/$productId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val text = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        return JSONObject(text)
    }

    /** 列出已缓存的演示查询 */
    fun listCachedQueries(): List<String> {
        return cacheDir().listFiles()
            ?.filter { it.name.startsWith("demo_") && it.name.endsWith(".jsonl") }
            ?.map { it.name.removePrefix("demo_").removeSuffix(".jsonl") }
            ?: emptyList()
    }
}
