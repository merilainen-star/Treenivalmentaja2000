package fi.merilainen.treenivalmentaja.data.analysis

import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class AnalysisTimeoutTest {
  private fun clients(calls: OkHttpClient) = listOf(
    AnthropicClient(apiKeys = { "test" }, calls = calls) to AnalysisModel.CLAUDE_SONNET,
    OpenAiClient(apiKeys = { "test" }, calls = calls) to AnalysisModel.GPT_SOL,
    GeminiClient(apiKeys = { "test" }, calls = calls) to AnalysisModel.GEMINI_FLASH,
  )

  @Test fun `programme gets longer reads and a finite deadline without slowing connection failure`() {
    val prose = AnalysisHttp.defaultCallFactory(AnalysisTask.PROSE)
    val programme = AnalysisHttp.defaultCallFactory(AnalysisTask.PROGRAM)
    assertEquals(120_000, prose.readTimeoutMillis)
    assertEquals(600_000, programme.readTimeoutMillis)
    assertEquals(150_000, prose.callTimeoutMillis)
    assertEquals(630_000, programme.callTimeoutMillis)
    assertEquals(15_000, prose.connectTimeoutMillis)
    assertEquals(15_000, programme.connectTimeoutMillis)
    assertSame(prose.connectionPool, programme.connectionPool)
  }

  @Test fun `socket and whole call timeouts are retryable timeouts for every provider`() = runTest {
    for (error in listOf(SocketTimeoutException("private request"), InterruptedIOException("timeout"))) {
      val calls = OkHttpClient.Builder().addInterceptor { throw error }.build()
      for ((client, model) in clients(calls)) {
        val failure = runCatching { client.analyse("programme", model, AnalysisTask.PROGRAM) }.exceptionOrNull()
        assertTrue(failure is AnalysisTimeoutException)
        assertTrue((failure as AnalysisException).canRetry)
        assertFalse(failure.message.orEmpty().contains("private request"))
        assertFalse(failure.message.orEmpty().contains("verkkoyhteyden"))
      }
    }
  }

  @Test fun `timeout while reading a successful response is distinguished and closes the body`() = runTest {
    var closed = 0
    val calls = OkHttpClient.Builder().addInterceptor { chain ->
      val source = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("private body")
        override fun timeout() = Timeout.NONE
        override fun close() { closed++ }
      }.buffer()
      val body = object : ResponseBody() {
        override fun contentType() = AnalysisHttp.JSON
        override fun contentLength() = -1L
        override fun source() = source
      }
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body(body).build()
    }.build()
    for ((client, model) in clients(calls)) {
      assertTrue(runCatching { client.analyse("programme", model, AnalysisTask.PROGRAM) }.exceptionOrNull() is AnalysisTimeoutException)
    }
    assertEquals(3, closed)
  }

  @Test fun `other connection failures do not claim the phone is offline or expose diagnostics`() = runTest {
    val calls = OkHttpClient.Builder().addInterceptor { throw IOException("private url and key") }.build()
    for ((client, model) in clients(calls)) {
      val failure = runCatching { client.analyse("programme", model, AnalysisTask.PROGRAM) }.exceptionOrNull()
      assertTrue(failure is AnalysisUnavailableException)
      assertEquals(AnalysisMessages.NETWORK, failure?.message)
      assertTrue((failure as AnalysisException).canRetry)
    }
  }
  @Test fun `a delayed HTTP response succeeds with time to finish and reports a real read timeout otherwise`() = runTest {
    for (timeoutMillis in listOf(5_000L, 50L)) {
      val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/") { exchange ->
        exchange.requestBody.use { it.readBytes() }
        Thread.sleep(250)
        try {
          val bytes = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""".toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } catch (_: IOException) {
          // The short-timeout client has closed its socket before the delayed response arrives.
        } finally {
          exchange.close()
        }
      }
      server.start()
      try {
        val calls = AnalysisHttp.defaultCallFactory(AnalysisTask.PROGRAM).newBuilder()
          .readTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val client = OpenAiClient(apiKeys = { "test" }, baseUrl = "http://127.0.0.1:${server.address.port}", calls = calls)
        val result = runCatching { client.analyse("programme", AnalysisModel.GPT_SOL, AnalysisTask.PROGRAM) }
        if (timeoutMillis == 50L) assertTrue(result.exceptionOrNull() is AnalysisTimeoutException)
        else assertEquals("ok", result.getOrThrow())
      } finally {
        server.stop(0)
      }
    }
  }

}
