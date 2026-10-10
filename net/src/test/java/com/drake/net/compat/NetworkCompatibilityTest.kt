package com.drake.net.compat

import android.app.Application
import com.drake.net.Get
import com.drake.net.Net
import com.drake.net.NetConfig
import com.drake.net.convert.NetConverter
import com.drake.net.exception.ConvertException
import com.drake.net.interceptor.RetryInterceptor
import com.drake.net.interceptor.LogRecordInterceptor
import com.drake.net.log.LogRecorder
import com.drake.net.okhttp.toNetOkhttp
import com.drake.net.request.BodyRequest
import com.drake.net.request.UrlRequest
import com.drake.net.utils.md5
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 通过本地服务器验证公开请求 API 和真实 OkHttp 网络链路，无需访问外部服务。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class NetworkCompatibilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var previousClient: OkHttpClient

    @Before
    fun setUp() {
        previousClient = NetConfig.okHttpClient
        NetConfig.runningCalls.clear()
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .toNetOkhttp()
            .build()
        NetConfig.okHttpClient = client
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
        NetConfig.runningCalls.clear()
        NetConfig.okHttpClient = previousClient
    }

    @Test
    fun getSendsEncodedQueryAndConvertsStringResponse() {
        server.enqueue(MockResponse(body = "response"))
        val result = UrlRequest().apply {
            setUrl(server.url("/search"))
            param("q", "中文 & query")
            setHeader("X-Test", "compatibility")
        }.execute<String>()

        val recorded = recordedRequest()
        assertEquals("response", result)
        assertEquals("GET", recorded.method)
        assertEquals("中文 & query", recorded.url.queryParameter("q"))
        assertEquals("compatibility", recorded.headers["X-Test"])
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun formUploadPreservesEncodingAndNotifiesEveryListener() {
        server.enqueue(MockResponse(body = "uploaded"))
        val first = ProgressRecorder()
        val second = ProgressRecorder()
        val result = BodyRequest().apply {
            setUrl(server.url("/form"))
            param("name", "中文 & value")
            param("enabled", true)
            addUploadListener(first)
            addUploadListener(second)
        }.execute<String>()

        val recorded = recordedRequest()
        assertEquals("uploaded", result)
        assertEquals("POST", recorded.method)
        assertEquals("application/x-www-form-urlencoded", recorded.headers["Content-Type"])
        assertEquals("name=%E4%B8%AD%E6%96%87+%26+value&enabled=true", recorded.body!!.utf8())
        for (progress in listOf(first, second)) {
            assertEquals(1, progress.events.count { it.finished })
            assertEquals(recorded.bodySize, progress.events.last().current)
            assertEquals(recorded.bodySize, progress.events.last().total)
        }
    }

    @Test
    fun multipartUploadContainsFormFieldAndFileContent() {
        server.enqueue(MockResponse(body = "uploaded"))
        val file = temporaryFolder.newFile("upload.txt").apply { writeText("file-content") }
        val progress = ProgressRecorder()
        assertEquals("uploaded", BodyRequest().apply {
            setUrl(server.url("/upload"))
            param("description", "form-value")
            param("file", file)
            addUploadListener(progress)
        }.execute<String>())

        val recorded = recordedRequest()
        val body = recorded.body!!.utf8()
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
        assertTrue(body.contains("name=\"description\""))
        assertTrue(body.contains("form-value"))
        assertTrue(body.contains("name=\"file\"; filename=\"upload.txt\""))
        assertTrue(body.contains("file-content"))
        assertEquals(recorded.bodySize, progress.events.last().current)
        assertTrue(progress.events.last().finished)
    }

    @Test
    fun downloadWritesExpectedBytesAndCompletesEveryProgressListener() {
        val expected = ByteArray(32 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(expected)).build())
        val destination = File(temporaryFolder.root, "download.bin")
        val first = ProgressRecorder()
        val second = ProgressRecorder()
        val result = UrlRequest().apply {
            setUrl(server.url("/download.bin"))
            setDownloadDir(destination)
            setDownloadTempFile()
            addDownloadListener(first)
            addDownloadListener(second)
        }.execute<File>()

        assertEquals(destination.canonicalFile, result.canonicalFile)
        assertArrayEquals(expected, result.readBytes())
        assertFalse(File(destination.path + ".downloading").exists())
        for (progress in listOf(first, second)) {
            assertEquals(1, progress.events.count { it.finished })
            assertEquals(ProgressSnapshot(expected.size.toLong(), expected.size.toLong(), true), progress.events.last())
        }
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun matchingLocalFileMd5ClosesUnusedHttpResponseAndCompletesItsCall() {
        val destination = temporaryFolder.newFile("existing.bin").apply { writeText("existing-payload") }
        val expected = destination.readBytes()
        server.enqueue(MockResponse.Builder()
            .body("existing-payload")
            .addHeader("Content-MD5", destination.md5(base64 = true)!!)
            .build())
        val responseBodyEnds = AtomicInteger()
        val callEnds = AtomicInteger()
        val progress = ProgressRecorder()
        val result = UrlRequest().apply {
            setUrl(server.url("/existing.bin"))
            setDownloadDir(destination)
            setDownloadMd5Verify()
            setDownloadTempFile()
            addDownloadListener(progress)
            setClient {
                eventListener(object : EventListener() {
                    override fun responseBodyEnd(call: Call, byteCount: Long) { responseBodyEnds.incrementAndGet() }
                    override fun callEnd(call: Call) { callEnds.incrementAndGet() }
                })
            }
        }.execute<File>()

        assertEquals(destination.canonicalFile, result.canonicalFile)
        assertArrayEquals(expected, result.readBytes())
        assertFalse(File(destination.path + ".downloading").exists())
        assertEquals(listOf(ProgressSnapshot(expected.size.toLong(), expected.size.toLong(), true)), progress.events)
        assertEquals(1, responseBodyEnds.get())
        assertEquals(1, callEnds.get())
        assertEquals("/existing.bin", recordedRequest().url.encodedPath)
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun chunkedDownloadReportsUnknownLengthAndCompletesAtEof() {
        server.enqueue(MockResponse.Builder().chunkedBody("chunked-response", 3).build())
        val progress = ProgressRecorder(interval = Long.MAX_VALUE)
        assertEquals("chunked-response", UrlRequest().apply {
            setUrl(server.url("/chunked"))
            addDownloadListener(progress)
        }.execute<String>())

        assertEquals(listOf(ProgressSnapshot(16L, -1L, true)), progress.events)
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun closingUnreadResponseRemovesCallFromRunningQueue() {
        server.enqueue(MockResponse(body = "unread-body"))
        val response = UrlRequest().apply {
            setUrl(server.url("/unread"))
            setId("unread")
        }.execute<Response>()

        assertNotNull(Net.getRequestById("unread"))
        response.close()
        assertNull(Net.getRequestById("unread"))
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun retriesFailedHttpResponseAndReplaysUploadWithFreshProgress() {
        server.enqueue(MockResponse(code = 500, body = "retry"))
        server.enqueue(MockResponse(body = "success"))
        val progress = ProgressRecorder()
        val result = BodyRequest().apply {
            setUrl(server.url("/retry"))
            setClient { addInterceptor(RetryInterceptor(retryCount = 1)) }
            body = "repeatable".toRequestBody("text/plain".toMediaType())
            addUploadListener(progress)
        }.execute<String>()

        assertEquals("success", result)
        assertEquals("repeatable", recordedRequest().body!!.utf8())
        assertEquals("repeatable", recordedRequest().body!!.utf8())
        assertEquals(2, progress.events.count { it.finished })
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun stopsAfterConfiguredRetryLimit() {
        repeat(3) { server.enqueue(MockResponse(code = 500, body = "retry")) }
        UrlRequest().apply {
            setUrl(server.url("/limit"))
            setClient { addInterceptor(RetryInterceptor(retryCount = 2)) }
        }.execute<Response>().use { assertEquals(500, it.code) }

        assertEquals(3, server.requestCount)
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun failedSynchronousStringConversionClosesResponseAndRemovesItsCall() {
        server.enqueue(MockResponse(code = 500, body = "failure"))

        assertThrows(ConvertException::class.java) {
            UrlRequest().apply { setUrl(server.url("/conversion-error")) }.execute<String>()
        }
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun failedTypeBasedConversionClosesResponseAndRemovesItsCall() {
        server.enqueue(MockResponse(code = 500, body = "failure"))

        assertThrows(ConvertException::class.java) {
            UrlRequest().apply { setUrl(server.url("/type-conversion-error")) }.execute<String>(String::class.java)
        }
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun oneShotUploadIsNotReplayedByRetryInterceptor() {
        server.enqueue(MockResponse(code = 500, body = "failure"))
        server.enqueue(MockResponse(body = "must-not-be-used"))
        val writes = AtomicInteger()
        val oneShot = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                check(writes.incrementAndGet() == 1) { "一次性请求体被重复发送" }
                sink.writeUtf8("one-shot")
            }
        }
        BodyRequest().apply {
            setUrl(server.url("/one-shot"))
            setClient { addInterceptor(RetryInterceptor(retryCount = 2)) }
            body = oneShot
        }.execute<Response>().use { assertEquals(500, it.code) }

        assertEquals(1, writes.get())
        assertEquals(1, server.requestCount)
        assertEquals("one-shot", recordedRequest().body!!.utf8())
    }

    @Test
    fun cancelIdCancelsInFlightCallAndRemovesItsQueueEntry() {
        // 服务端读到请求后延迟响应，保证取消的是进行中的真实网络请求。
        server.enqueue(MockResponse.Builder().body("late-response").headersDelay(2, TimeUnit.SECONDS).build())
        val finished = CountDownLatch(1)
        val failure = AtomicReference<IOException>()
        val call = UrlRequest().apply {
            setUrl(server.url("/cancel"))
            setId("cancel-me")
        }.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.set(e); finished.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
        })
        recordedRequest()
        assertNotNull(Net.getRequestById("cancel-me"))
        assertTrue(Net.cancelId("cancel-me"))
        assertTrue(finished.await(5, TimeUnit.SECONDS))

        assertTrue(call.isCanceled())
        assertNotNull(failure.get())
        assertNull(Net.getRequestById("cancel-me"))
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun interruptedResponseReadRemovesFailedCallFromQueue() {
        server.enqueue(MockResponse.Builder()
            .body("truncated-response".repeat(1000))
            .onResponseBody(SocketEffect.CloseSocket())
            .build())

        assertThrows(IOException::class.java) {
            UrlRequest().apply { setUrl(server.url("/truncated")) }.execute<String>()
        }
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun deferredCancellationCancelsItsRealNetworkCall() = runBlocking {
        server.enqueue(MockResponse.Builder().body("late-response").headersDelay(2, TimeUnit.SECONDS).build())
        val deferred = Get<String>(server.url("/deferred-cancel").toString()) { setId("deferred-cancel") }
        recordedRequest()
        val call = NetConfig.runningCalls.first { it.get()?.request()?.url?.encodedPath == "/deferred-cancel" }.get()!!
        deferred.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(deferred.isCancelled)
        awaitNoRunningCalls()
    }

    @Test
    fun parentScopeCancellationCancelsItsNetworkChildren() = runBlocking {
        server.enqueue(MockResponse.Builder().body("late-response").headersDelay(2, TimeUnit.SECONDS).build())
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val deferred = scope.Get<String>(server.url("/parent-cancel").toString())
        recordedRequest()
        val call = NetConfig.runningCalls.first { it.get()?.request()?.url?.encodedPath == "/parent-cancel" }.get()!!
        parent.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(deferred.isCancelled)
        awaitNoRunningCalls()
    }

    @Test
    fun successfulDeferredLeavesNoActiveSupervisorInParentScope() = runBlocking {
        server.enqueue(MockResponse(body = "success"))
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val deferred = scope.Get<String>(server.url("/success").toString())
        assertEquals("success", deferred.await())
        deferred.join()

        assertTrue("完成请求不应留下阻止父作用域结束的监督节点", parent.children.none())
        parent.complete()
        withTimeout(5000) { parent.join() }
    }

    @Test
    fun failedDeferredDoesNotCancelSiblingRequestsOrParent() = runBlocking {
        server.enqueue(MockResponse(code = 500, body = "failure"))
        server.enqueue(MockResponse(body = "sibling-success"))
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val failure = scope.Get<String>(server.url("/failure").toString())
        assertTrue(runCatching { failure.await() }.isFailure)
        assertTrue(parent.isActive)
        val sibling = scope.Get<String>(server.url("/sibling").toString())
        assertEquals("sibling-success", sibling.await())
        failure.join()
        sibling.join()
        assertTrue(parent.children.none())
        parent.complete()
        withTimeout(5000) { parent.join() }
        awaitNoRunningCalls()
    }

    @Test
    fun cancellationDuringResponseConversionClosesTheReceivedResponse(): Unit = runBlocking {
        server.enqueue(MockResponse(body = "response"))
        val conversionStarted = CountDownLatch(1)
        val finishConversion = CountDownLatch(1)
        val receivedResponse = AtomicReference<Response>()
        val deferred = Get<String>(server.url("/conversion-cancel").toString()) {
            converter = object : NetConverter {
                @Suppress("UNCHECKED_CAST")
                override fun <R> onConvert(succeed: Type, response: Response): R {
                    receivedResponse.set(response)
                    conversionStarted.countDown()
                    assertTrue(finishConversion.await(5, TimeUnit.SECONDS))
                    return "converted" as R
                }
            }
        }
        assertTrue(conversionStarted.await(5, TimeUnit.SECONDS))
        deferred.cancelAndJoin()
        finishConversion.countDown()
        awaitNoRunningCalls()

        assertTrue(deferred.isCancelled)
        assertNotNull(receivedResponse.get())
        assertThrows(IllegalStateException::class.java) { receivedResponse.get().body.source().readByte() }
    }

    @Test
    fun converterFailureClosesResponseAndRemovesItsCall() = runBlocking {
        server.enqueue(MockResponse(body = "response"))
        val deferred = Get<String>(server.url("/conversion-failure").toString()) {
            converter = object : NetConverter {
                override fun <R> onConvert(succeed: Type, response: Response): R =
                    throw IllegalArgumentException("conversion failed")
            }
        }

        assertTrue(runCatching { deferred.await() }.isFailure)
        awaitNoRunningCalls()
    }

    @Test
    fun enabledRequestLoggingDoesNotConsumeOneShotUpload() {
        server.enqueue(MockResponse(body = "uploaded"))
        val writes = AtomicInteger()
        val body = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                check(writes.incrementAndGet() == 1) { "日志消耗了一次性请求体" }
                sink.writeUtf8("one-shot-payload")
            }
        }
        val wasLogRecorderEnabled = LogRecorder.enabled
        LogRecorder.enabled = false
        try {
            assertEquals("uploaded", BodyRequest().apply {
                setUrl(server.url("/logged-one-shot"))
                setClient { addInterceptor(LogRecordInterceptor(enabled = true)) }
                this.body = body
            }.execute<String>())
            assertEquals(1, writes.get())
            assertEquals("one-shot-payload", recordedRequest().body!!.utf8())
        } finally {
            LogRecorder.enabled = wasLogRecorderEnabled
        }
    }

    private suspend fun awaitNoRunningCalls() {
        withTimeout(5000) {
            while (NetConfig.runningCalls.isNotEmpty()) delay(10)
        }
    }

    private fun recordedRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("本地服务器未在超时前收到请求")
}
