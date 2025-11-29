package me.rerere.rikkahub.data.ai.mcp.transport

import android.util.Log
import io.ktor.http.URLBuilder
import io.ktor.http.path
import io.ktor.http.takeFrom
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.mcp.McpJson
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val TAG = "SseClientTransport"

@OptIn(ExperimentalAtomicApi::class)
internal class SseClientTransport(
    private val client: OkHttpClient,
    private val urlString: String,
    private val headers: List<Pair<String, String>>,
) : AbstractTransport() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventSourceFactory = EventSources.createFactory(client)
    // initialized 现在表示是否处于"活跃"状态（未被显式关闭）
    private val active: AtomicBoolean = AtomicBoolean(false)
    private var session: EventSource? = null
    // endpoint 只能完成一次，所以重连时不应重新 new 它，除非我们重置它（比较麻烦）
    // 这里假设 endpoint 地址在重连期间不会变
    private val endpoint = CompletableDeferred<String>()

    private var job: Job? = null

    private val baseUrl by lazy {
        URLBuilder()
            .takeFrom(urlString)
            .apply {
                path() // set path to empty
                parameters.clear() //  clear parameters
            }
            .build()
            .toString()
            .trimEnd('/')
    }

    override suspend fun start() {
        if (active.getAndSet(true)) {
            error(
                "SSEClientTransport already started! " +
                    "If using Client class, note that connect() calls start() automatically.",
            )
        }
        
        connectInternal()

        // 等待首次连接成功（获取 endpoint）
        // 如果首次连接就失败且重连也没用，这里会超时抛出异常
        // 但有了重连机制，它可能会挂起直到连上
        try {
            withTimeout(30000) {
                endpoint.await()
                Log.i(TAG, "start: Connected to endpoint ${endpoint.getCompleted()}")
            }
        } catch (e: Exception) {
            // 如果首次连接超时，我们应该关闭吗？
            // 为了保险，如果是首次失败，我们让它抛出异常
            if (!endpoint.isCompleted) {
                close()
                throw e
            }
        }
    }

    // ASH PATCH: 提取连接逻辑以支持重连
    private fun connectInternal() {
        if (!active.get()) return

        Log.i(TAG, "Connecting to $urlString ...")
        
        val request = Request.Builder()
            .url(urlString)
            .headers(
                Headers.Builder()
                    .apply {
                        for ((key, value) in headers) {
                            add(key, value)
                        }
                    }
                    .build()
            )
            .addHeader("Accept", "text/event-stream")
            .addHeader("User-Agent", "RikkaHub/${BuildConfig.VERSION_NAME}")
            .build()

        session = eventSourceFactory.newEventSource(
            request = request,
            listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    super.onOpen(eventSource, response)
                    Log.i(TAG, "onOpen: Connection established")
                }

                override fun onClosed(eventSource: EventSource) {
                    super.onClosed(eventSource)
                    Log.i(TAG, "onClosed: Server closed connection")
                    // 服务端关闭，尝试重连
                    scheduleReconnect()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    super.onFailure(eventSource, t, response)
                    Log.e(TAG, "onFailure: Connection lost ($t). Attempting reconnect...")
                    
                    // ASH PATCH: 关键点！不要报错，不要关闭，而是重连！
                    scheduleReconnect()
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    Log.i(TAG, "onEvent($baseUrl):  #$id($type)") // 简化日志，不打印 data 防止刷屏
                    when (type) {
                        "error" -> {
                            Log.e(TAG, "SSE Error Event: $data")
                            // 某些服务端可能会发 error 事件，视情况重连
                        }

                        "open" -> { }

                        "endpoint" -> {
                            if (!endpoint.isCompleted) {
                                val endpointData =
                                    if (data.startsWith("http://") || data.startsWith("https://")) {
                                        data
                                    } else {
                                        baseUrl + if (data.startsWith("/")) data else "/$data"
                                    }
                                Log.i(TAG, "onEvent: endpoint received: $endpointData")
                                endpoint.complete(endpointData)
                            }
                        }

                        else -> {
                            scope.launch {
                                try {
                                    val message = McpJson.decodeFromString<JSONRPCMessage>(data)
                                    _onMessage(message)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Message decode failed", e)
                                    // _onError(e) // 解析失败不应导致断连
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (!active.get()) return
        
        scope.launch {
            delay(3000) // 等待 3 秒
            if (active.get()) {
                Log.i(TAG, "Reconnecting now...")
                connectInternal()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(message: JSONRPCMessage) {
        if (!endpoint.isCompleted) {
            error("Not connected (endpoint not received)")
        }

        Log.i(TAG, "send: POSTing message...")

        try {
            val request = Request.Builder()
                .url(endpoint.getCompleted())
                .apply {
                    for ((key, value) in headers) {
                        addHeader(key, value)
                    }
                }
                .post(
                    McpJson.encodeToString(message).toRequestBody(
                        contentType = "application/json".toMediaType(),
                    )
                )
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                val text = response.body.string()
                error("Error POSTing to endpoint (HTTP ${response.code}): $text")
            } else {
                Log.i(TAG, "send: POST successful")
            }
        } catch (e: Exception) {
            // 发送失败通常意味着网络问题，这里抛出异常让上层知道
            // 但 transport 本身会通过 onFailure 自动重连
            _onError(e)
            throw e
        }
    }

    override suspend fun close() {
        if (!active.compareAndSet(true, false)) {
            // 已经关闭了
            return
        }

        session?.cancel()
        _onClose() // 通知上层已关闭
        job?.cancelAndJoin()
    }
}
