package com.example.globaltranslation.data.provider

import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.PhotoTranslator
import com.example.globaltranslation.core.util.TranslationBatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeepSeekTranslator(
    private val client: OkHttpClient,
    private val endpoint: String = "https://api.deepseek.com/chat/completions",
    private val model: String = DeepSeekProtocol.MODEL
) : PhotoTranslator {
    override suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String): TranslationResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext TranslationResult(emptyMap(), "请先在设置中填写 DeepSeek API Key。")
            val parts = TranslationBatches.parts(blocks)
            val results = linkedMapOf<String, String>()
            var failure: String? = null
            for (batch in TranslationBatches.batches(parts)) {
                coroutineContext.ensureActive()
                try {
                    val request = Request.Builder().url(endpoint)
                        .header("Authorization", "Bearer $apiKey")
                        .post(DeepSeekProtocol.request(batch, options, model).toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    val response = execute(request)
                    results.putAll(DeepSeekProtocol.response(response, batch.map { it.id }.toSet()))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failure = when (error) {
                        is ApiStatusException -> when (error.status) {
                            401, 403 -> "API Key 无效或无访问权限，请在设置中更换。"
                            402 -> "DeepSeek 账户余额不足，请充值后重试。"
                            429 -> "请求过于频繁，请稍后手动重试。"
                            in 500..599 -> "DeepSeek 服务暂时不可用，请稍后重试。"
                            else -> "翻译请求未被接受，请检查配置后重试。"
                        }
                        is InterruptedIOException -> "翻译请求超时，请检查网络后重试。"
                        is IOException -> "无法连接 DeepSeek，请检查网络后重试。"
                        else -> "翻译结果格式异常或不完整，请重试。"
                    }
                    break
                }
            }
            TranslationResult(TranslationBatches.assemble(parts, results), failure)
        }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw ApiStatusException(response.code)
                        val body = response.body ?: throw IOException("Empty response")
                        // Bound malformed server responses; never log remote bodies or headers.
                        val source = body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        require(source.buffer.size <= MAX_RESPONSE_BYTES)
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private class ApiStatusException(val status: Int) : Exception()
    private companion object { const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024 }
}
