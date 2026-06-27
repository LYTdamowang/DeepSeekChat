package com.deepseekchat.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepSeekApi @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .socketFactory(KeepAliveSocketFactory())
        .build()
    private val currentCall = AtomicReference<okhttp3.Call?>(null)
    private val currentResponse = AtomicReference<okhttp3.Response?>(null)

    fun cancelStreaming() {
        val call = currentCall.getAndSet(null)
        val response = currentResponse.getAndSet(null)
        if (call != null || response != null) {
            Thread {
                call?.cancel()
                response?.close()
            }.start()
        }
    }

    fun streamChat(apiKey: String, request: ChatRequest): Flow<StreamEvent> = flow {
        val requestJson = json.encodeToString(ChatRequest.serializer(), request)
        Log.d("DeepSeekAPI", "Request: $requestJson")

        val httpRequest = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .build()

        val call = client.newCall(httpRequest)
        currentCall.set(call)
        val response = try {
            call.execute()
        } finally {
            currentCall.compareAndSet(call, null)
        }

        currentResponse.set(response)
        try {
            Log.d("DeepSeekAPI", "HTTP ${response.code}, type: ${response.header("content-type")}")

            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                Log.e("DeepSeekAPI", "Error: $err")
                val msg = parseApiErrorMessage(response.code, err)
                throw Exception(msg)
            }

            val body = response.body ?: throw Exception("服务器返回空响应")
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val chunk = json.decodeFromString<ChatStreamChunk>(data)
                        val delta = chunk.choices?.firstOrNull()?.delta ?: continue
                        val reasoningContent = delta.reasoningContent
                        val text = delta.content
                        if (!reasoningContent.isNullOrEmpty()) emit(StreamEvent.Reasoning(reasoningContent))
                        if (!text.isNullOrEmpty()) emit(StreamEvent.Content(text))
                    } catch (_: Exception) { }
                }
            }
        } finally {
            currentResponse.compareAndSet(response, null)
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseApiErrorMessage(code: Int, body: String): String {
        // Try to extract error message from API response body
        val apiMsg = try {
            val obj = org.json.JSONObject(body)
            obj.optJSONObject("error")?.optString("message", "")
                ?: ""
        } catch (_: Exception) { "" }

        val detail = if (apiMsg.isNotBlank()) " ($apiMsg)" else ""
        return when (code) {
            401 -> "认证失败，请检查 API Key 是否正确$detail"
            402 -> "账户余额不足，请充值$detail"
            403 -> "访问被拒绝$detail"
            429 -> "请求太频繁，请稍后重试$detail"
            500 -> "服务器内部错误$detail"
            502 -> "服务器网关错误$detail"
            503 -> "服务暂时不可用，请稍后重试$detail"
            else -> "API 请求失败 (HTTP $code)$detail"
        }
    }
}

private class KeepAliveSocketFactory : javax.net.SocketFactory() {
    private val default = javax.net.SocketFactory.getDefault()

    private fun enableKeepAlive(socket: java.net.Socket): java.net.Socket {
        socket.keepAlive = true
        return socket
    }

    override fun createSocket() = enableKeepAlive(default.createSocket())
    override fun createSocket(host: String, port: Int) =
        enableKeepAlive(default.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
        enableKeepAlive(default.createSocket(host, port, localHost, localPort))
    override fun createSocket(addr: java.net.InetAddress, port: Int) =
        enableKeepAlive(default.createSocket(addr, port))
    override fun createSocket(addr: java.net.InetAddress, port: Int, localAddr: java.net.InetAddress, localPort: Int) =
        enableKeepAlive(default.createSocket(addr, port, localAddr, localPort))
}
