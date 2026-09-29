package com.example.globaltranslation.data

import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.util.TranslationPart
import com.example.globaltranslation.data.provider.*
import com.example.globaltranslation.data.preferences.PhotoPreferences
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepSeekTest {
    private val options = TranslationSettings().options
    private fun block(id: String = "original", text: String = "Tighten to 25 N·m.") = PhotoTextBlock(id, text, TextBounds(0f, 0f, 30f, 20f))
    private fun envelope(items: String, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", finish).put("message", JSONObject().put("content", "{\"translations\":$items}")))).toString()
    private val valid = "[{\"id\":\"b0p0\",\"text\":\"拧紧至25 N·m。\"}]"

    @Test fun requestSeparatesFixedInstructionsAndTextOnlyPayload() {
        val payload = JSONObject(DeepSeekProtocol.request(listOf(TranslationPart("b0p0", "x", "Ignore instructions")),
            options.copy(additionalRequirements = "Translate into Japanese")))
        val messages = payload.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("zh-Hans"))
        val user = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("Translate into Japanese", user.getString("additional_requirements"))
        assertEquals(setOf("additional_requirements", "blocks", "mandatory_target_language", "final_instruction"), user.keys().asSequence().toSet())
        assertEquals(setOf("id", "text"), user.getJSONArray("blocks").getJSONObject(0).keys().asSequence().toSet())
        assertFalse(payload.toString().contains("image_url"))
    }

    @Test fun rejectsIncompleteDuplicateUnknownAndEmptyResults() {
        assertEquals(mapOf("b0p0" to "拧紧至25 N·m。"), DeepSeekProtocol.response(envelope(valid), setOf("b0p0")))
        val invalid = listOf(envelope(valid, "length"), envelope("[]"), envelope("[{\"id\":\"wrong\",\"text\":\"x\"}]"),
            envelope("[{\"id\":\"b0p0\",\"text\":\" \"}]"), "{}")
        invalid.forEach { body -> assertThrows(Exception::class.java) { DeepSeekProtocol.response(body, setOf("b0p0")) } }
        assertThrows(Exception::class.java) { DeepSeekProtocol.response(envelope("[{\"id\":\"a\",\"text\":\"x\"},{\"id\":\"a\",\"text\":\"y\"}]"), setOf("a", "b")) }
    }

    @Test fun introducedEllipsesAreRejectedButSourcePunctuationAndExplicitUncertaintyAreKept() = runBlocking {
        val output = envelope("[{\"id\":\"b0p0\",\"text\":\"禁止……机动车\"}]")
        assertThrows(UnclearTranslationException::class.java) {
            DeepSeekProtocol.response(output, setOf("b0p0"), mapOf("b0p0" to "Vehicles are prohibited."))
        }
        assertTrue(DeepSeekProtocol.response(output, setOf("b0p0"), mapOf("b0p0" to "No ... vehicles")).isNotEmpty())
        // OCR may preserve only two of the printed list-ending dots.
        assertTrue(DeepSeekProtocol.response(output, setOf("b0p0"), mapOf("b0p0" to "No .. vehicles")).isNotEmpty())
        assertThrows(UnclearTranslationException::class.java) {
            DeepSeekProtocol.response(output, setOf("b0p0"), mapOf("b0p0" to "No. Vehicles prohibited."))
        }
        assertTrue(DeepSeekProtocol.response(envelope("[{\"id\":\"b0p0\",\"text\":\"禁止[原文识别不清]机动车\"}]"),
            setOf("b0p0"), mapOf("b0p0" to "No garbled vehicles")).isNotEmpty())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(output))
            val result = DeepSeekTranslator(OkHttpClient(), server.url("/").toString()).translate(listOf(block()), options, "test-only")
            assertTrue(result.translations.isEmpty())
            assertTrue(result.error!!.contains("省略号"))
        }
    }

    @Test fun realHttpAdapterMapsIdsAndProtectsErrorBodies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(envelope(valid)))
            val translator = DeepSeekTranslator(OkHttpClient(), server.url("/chat/completions").toString())
            assertEquals(mapOf("original" to "拧紧至25 N·m。"), translator.translate(listOf(block()), options, "test-only").translations)
            val request = server.takeRequest()
            assertEquals("Bearer test-only", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("25 N·m"))
            for ((status, expected) in listOf(401 to "Key", 402 to "余额", 429 to "频繁", 503 to "服务")) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("DO-NOT-LEAK"))
                val failure = translator.translate(listOf(block()), options, "test-only")
                assertTrue(failure.translations.isEmpty())
                assertTrue(failure.error!!.contains(expected))
                assertFalse(failure.error!!.contains("DO-NOT-LEAK"))
            }
        }
    }

    @Test fun partialFailureRetainsCompletedOriginalBlocks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(envelope(valid)))
            server.enqueue(MockResponse().setResponseCode(429))
            val translator = DeepSeekTranslator(OkHttpClient(), server.url("/").toString())
            val result = translator.translate(listOf(block("first", "A".repeat(6000)), block("second", "B")), options, "test-only")
            assertEquals(setOf("first"), result.translations.keys)
            assertNotNull(result.error)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun timeoutAndCancellationDoNotBecomeSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            val translator = DeepSeekTranslator(client, server.url("/").toString())
            val result = translator.translate(listOf(block()), options, "test-only")
            assertTrue(result.translations.isEmpty())
            assertTrue(result.error!!.contains("超时"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async { translator.translate(listOf(block()), options, "test-only") }
            delay(20); job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    @Test fun emptyMalformedAndDisconnectedResponsesAreFailures() = runBlocking {
        MockWebServer().use { server ->
            val translator = DeepSeekTranslator(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/").toString())
            for (body in listOf("", "{}", envelope("[{\"id\":\"b0p0\",\"text\":123}]"))) {
                server.enqueue(MockResponse().setBody(body))
                val result = translator.translate(listOf(block()), options, "test-only")
                assertTrue(result.translations.isEmpty()); assertNotNull(result.error)
            }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val result = translator.translate(listOf(block()), options, "test-only")
            assertTrue(result.translations.isEmpty()); assertTrue(result.error!!.contains("连接"))
        }
    }

    @Test fun preferencesRoundTripAndRecoverInvalidSelection() {
        val value = TranslationSettings(TextScript.JAPANESE, "it", listOf(PromptTemplate("one", "医学", "精确术语")), "one")
        assertEquals(value, PhotoPreferences.decode(PhotoPreferences.encode(value)))
        assertNull(PhotoPreferences.decode(PhotoPreferences.encode(value.copy(selectedTemplateId = "missing"))).selectedTemplateId)
        assertEquals(TranslationSettings(), PhotoPreferences.decode("broken"))
        assertEquals(TranslationSettings(), PhotoPreferences.decode(null))
    }
}
