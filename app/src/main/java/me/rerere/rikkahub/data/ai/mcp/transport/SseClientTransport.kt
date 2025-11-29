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
    private val active: AtomicBoolean = AtomicBoolean(false)
    private var session: EventSource? = null
    
    // ASH FIX V2: 使用 Volatile var 以便重置，不再是 final val
    @Volatile 
    private var endpointDeferred = CompletableDeferred<String>()

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
            error("SSEClientTransport already started!")
        }
        
        connectInternal()

        try {
            withTimeout(30000) {
                // 等待当前的 deferred 完成
                val url = endpointDeferred.await()
                Log.i(TAG, "start: Connected to endpoint $url")
            }
        } catch (e: Exception) {
            if (!endpointDeferred.isCompleted) {
                close()
                throw e
            }
        }
    }

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
                    scheduleReconnect()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    super.onFailure(eventSource, t, response)
                    Log.e(TAG, "onFailure: Connection lost ($t). Attempting reconnect...")
                    scheduleReconnect()
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    Log.i(TAG, "onEvent($baseUrl):  #$id($type)")
                    when (type) {
                        "error" -> Log.e(TAG, "SSE Error: $data")
                        "open" -> { }
                        "endpoint" -> {
                            // ASH FIX V2: 无论如何，收到 endpoint 就尝试完成当前的 deferred
                            val endpointData =
                                if (data.startsWith("http://") || data.startsWith("https://")) {
                                    data
                                } else {
                                    baseUrl + if (data.startsWith("/")) data else "/$data"
                                }
                            Log.i(TAG, "onEvent: endpoint received: $endpointData")
                            
                            // 尝试完成当前的 deferred
                            endpointDeferred.complete(endpointData)
                        }
                        else -> {
                            scope.launch {
                                try {
                                    val message = McpJson.decodeFromString<JSONRPCMessage>(data)
                                    _onMessage(message)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Message decode failed", e)
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
            // ASH FIX V2: 在重连等待期间，重置 deferred，准备接收新的 Session ID
            // 只有当旧的已经完成时才重置，避免 start() 还没等到结果就被重置了
            if (endpointDeferred.isCompleted) {
                Log.i(TAG, "Resetting endpoint for new session...")
                endpointDeferred = CompletableDeferred()
            }
            
            delay(3000)
            if (active.get()) {
                Log.i(TAG, "Reconnecting now...")
                connectInternal()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(message: JSONRPCMessage) {
        // ASH FIX V2: 获取当前的 deferred 实例
        val currentDeferred = endpointDeferred
        
        if (!currentDeferred.isCompleted) {
            // 如果正在重连中，等待新的 Session ID
            Log.i(TAG, "send: Waiting for reconnection/endpoint...")
            currentDeferred.await()
        }

        // 获取最新的 URL
        val url = currentDeferred.getCompleted()
        Log.i(TAG, "send: POSTing to $url ...")

        try {
            val request = Request.Builder()
                .url(url) 
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
            // 发送失败不直接关闭，transport 会自动重连
            _onError(e)
            throw e
        }
    }

    override suspend fun close() {
        if (!active.compareAndSet(true, false)) return
        session?.cancel()
        _onClose()
        job?.cancelAndJoin()
    }
}
