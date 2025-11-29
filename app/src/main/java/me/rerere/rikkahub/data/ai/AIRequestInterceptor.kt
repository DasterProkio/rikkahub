package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AIRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}

private fun Request.readBodyAsJson(): JsonElement? {
    val contentType = body?.contentType()
    if (contentType?.type == "application" && contentType.subtype == "json") {
        val buffer = okio.Buffer()
        buffer.use {
            body?.writeTo(it)
            return JsonInstant.parseToJsonElement(buffer.readUtf8())
        }
    }
    return null
}
