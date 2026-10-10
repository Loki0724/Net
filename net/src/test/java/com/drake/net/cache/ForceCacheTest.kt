package com.drake.net.cache

import com.drake.net.tag.NetTag
import okhttp3.Cache
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType
import okhttp3.OkHttpUtils
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 针对真实磁盘缓存验证升级后的格式兼容性、快照关闭和正文事务提交。 */
class ForceCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var httpCache: Cache
    private lateinit var forceCache: ForceCache

    @Before
    fun setUp() {
        httpCache = Cache(temporaryFolder.newFolder("cache"), 1024L * 1024)
        forceCache = ForceCache(OkHttpUtils.diskLruCache(httpCache))
    }

    @After
    fun tearDown() {
        httpCache.close()
    }

    @Test
    fun defaultKeysSeparateGetAndPostAndKeepLegacySha1() {
        val get = request()
        val post = get.newBuilder().post("first".toRequestBody()).build()
        val anotherPost = get.newBuilder().post("second".toRequestBody()).build()

        assertEquals(("GET" + get.url).encodeUtf8().sha1().hex(), ForceCache.key(get))
        assertNotEquals(ForceCache.key(get), ForceCache.key(post))
        // 默认键仍只由方法和 URL 决定；同一 URL 的多个 POST 需要调用方指定业务缓存键。
        assertEquals(ForceCache.key(post), ForceCache.key(anotherPost))
        store(get, "get")
        store(post, "post")
        assertEquals("get", cachedBody(get))
        assertEquals("post", cachedBody(post))
    }

    @Test
    fun customKeySupportsDifferentUrlsAndMethods() {
        val first = request().newBuilder()
            .tag(NetTag.CacheKey::class.java, NetTag.CacheKey("shared-result"))
            .post("payload".toRequestBody())
            .build()
        val second = request("http://example.com/another").newBuilder()
            .tag(NetTag.CacheKey::class.java, NetTag.CacheKey("shared-result"))
            .build()
        store(first, "shared")

        forceCache.get(second)!!.use {
            assertSame(second, it.request)
            assertEquals("GET", it.request.method)
            assertEquals("shared", it.body.string())
            assertNotNull(it.cacheResponse)
        }
    }

    @Test
    fun responseHeadersTimestampsAndBodySurviveClosingAndReopening() {
        val request = request().newBuilder().post("payload".toRequestBody()).build()
        val sent = System.currentTimeMillis() - 100
        val received = sent + 50
        val headers = headersOf("Content-Type", "text/plain; charset=utf-8", "X-Result", "first", "X-Result", "second")
        forceCache.put(response(request, "persisted".toResponseBody(), headers, sent, received)).use {
            assertEquals("persisted", it.body.string())
        }
        forceCache.flush()
        httpCache.close()
        httpCache = Cache(forceCache.directory, 1024L * 1024)
        forceCache = ForceCache(OkHttpUtils.diskLruCache(httpCache))

        forceCache.get(request)!!.use {
            assertEquals("persisted", it.body.string())
            assertEquals(listOf("first", "second"), it.headers.values("X-Result"))
            assertEquals(sent, it.sentRequestAtMillis)
            assertEquals(received, it.receivedResponseAtMillis)
            assertNull(it.header("OkHttp-Sent-Millis"))
            assertNull(it.header("OkHttp-Received-Millis"))
        }
        assertEquals(request.url.toString(), forceCache.urls().next())
        assertEquals(httpCache.directory, forceCache.directory)
    }

    @Test
    fun legacyJournalAndSha1EntryCanBeReadWithoutClearingExistingCache() {
        val request = request()
        val key = ForceCache.key(request)
        val contents = "legacy-body"
        val metadata = "${request.url}\nGET\n0\nHTTP/1.1 200 OK\n3\nContent-Length: ${contents.length}\nOkHttp-Sent-Millis: 100\nOkHttp-Received-Millis: 200\n"
        // 沿用升级前 OkHttp 4 的 journal v1 / appVersion 201105 / 双文件条目格式。
        // Cache 尚未初始化，先放入既有磁盘内容，再验证 OkHttp 5 不会清空或误判为损坏缓存。
        File(forceCache.directory, "$key.0").writeText(metadata, Charsets.UTF_8)
        File(forceCache.directory, "$key.1").writeText(contents, Charsets.UTF_8)
        File(forceCache.directory, "journal").writeText(
            "libcore.io.DiskLruCache\n1\n201105\n2\n\nCLEAN $key ${metadata.toByteArray().size} ${contents.toByteArray().size}\n",
            Charsets.UTF_8
        )

        forceCache.initialize()
        forceCache.get(request)!!.use {
            assertEquals(contents, it.body.string())
            assertEquals(100L, it.sentRequestAtMillis)
            assertEquals(200L, it.receivedResponseAtMillis)
        }
        assertEquals(request.url.toString(), forceCache.urls().next())
    }

    @Test
    fun expiredEntryReleasesSnapshotSoItCanBeReplaced() {
        val request = request()
        forceCache.put(response(request, "stale".toResponseBody(), received = 1L)).use { it.body.string() }
        val expired = request.newBuilder()
            .tag(NetTag.CacheValidTime::class.java, NetTag.CacheValidTime(1_000L))
            .build()

        assertNull(forceCache.get(expired))
        store(request, "fresh")
        assertEquals("fresh", cachedBody(expired))
        forceCache.remove(request)
        assertNull(forceCache.get(request))
    }

    @Test
    fun closingAfterPartialReadDrainsAndCommitsCompleteBody() {
        val request = request()
        val contents = "abcdefgh".repeat(8192)
        val response = forceCache.put(response(request, contents.toResponseBody()))
        assertEquals("a"[0].code.toByte(), response.body.source().readByte())
        response.close()

        assertEquals(1, forceCache.writeSuccessCount())
        assertEquals(0, forceCache.writeAbortCount())
        assertEquals(contents, cachedBody(request))
    }

    @Test
    fun readFailureAbortsPartialBodyAndAllowsAnotherWrite() {
        val request = request()
        val source = object : Source {
            private var first = true
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!first) throw IOException("network disconnected")
                first = false
                sink.writeUtf8("partial")
                return 7
            }

            override fun timeout() = Timeout()
            override fun close() = Unit
        }
        val response = forceCache.put(response(request, body(source)))
        assertThrows(IOException::class.java) { response.body.string() }
        response.close()

        assertEquals(0, forceCache.writeSuccessCount())
        assertEquals(1, forceCache.writeAbortCount())
        assertNull(forceCache.get(request))
        store(request, "retry")
        assertEquals("retry", cachedBody(request))
    }

    @Test
    fun closeTimeoutAbortsAndRestoresOriginalSourceDeadline() {
        val request = request()
        val timeout = Timeout().deadline(5, TimeUnit.SECONDS)
        val originalDeadline = timeout.deadlineNanoTime()
        var closed = false
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                // 真实 Socket 的读操作会遵守截止时间，这里用同样的 Timeout 模拟无法及时读完。
                while (true) {
                    timeout.throwIfReached()
                    Thread.yield()
                }
            }

            override fun timeout() = timeout
            override fun close() { closed = true }
        }
        forceCache.put(response(request, body(source))).close()

        assertTrue(closed)
        assertEquals(originalDeadline, timeout.deadlineNanoTime())
        assertEquals(1, forceCache.writeAbortCount())
        assertNull(forceCache.get(request))
    }

    @Test
    fun emptySuccessfulBodyIsCommittedAndUnsuccessfulResponseIsIgnored() {
        val request = request()
        val empty = response(request, "".toResponseBody()).newBuilder().code(204).build()
        forceCache.put(empty).close()
        assertEquals("", cachedBody(request))
        val error = response(request, "error".toResponseBody()).newBuilder().code(500).build()
        assertSame(error, forceCache.put(error))
        error.close()
        assertEquals("", cachedBody(request))
        assertEquals(1, forceCache.writeSuccessCount())
    }

    @Test
    fun missingNetworkResponseUsesRequestHeadersForVary() {
        val request = request().newBuilder().header("Accept-Language", "zh-CN").build()
        val headers = headersOf("Vary", "Accept-Language")
        val response = response(request, "localized".toResponseBody(), headers)

        with(ForceCache) {
            assertEquals(listOf("zh-CN"), response.varyHeaders().values("Accept-Language"))
        }
        forceCache.put(response).use { assertEquals("localized", it.body.string()) }
        assertEquals("localized", cachedBody(request))
    }

    @Test
    fun lenientUnicodeHeadersAndUnknownBodyLengthSurviveRoundTrip() {
        val request = request()
        val headers = Headers.Builder()
            .addUnsafeNonAscii("X-Label", "中文响应")
            .add("Content-Length", "unknown")
            .build()
        forceCache.put(response(request, "contents".toResponseBody(), headers)).use { it.body.string() }

        forceCache.get(request)!!.use {
            assertEquals("中文响应", it.header("X-Label"))
            assertEquals(-1L, it.body.contentLength())
            assertEquals("contents", it.body.string())
        }
    }

    @Test
    fun metadataUpdatePreservesAlreadyStoredBody() {
        val request = request()
        store(request, "body")
        forceCache.get(request)!!.use { cached ->
            val updated = response(request, "unused".toResponseBody(), headersOf("X-Version", "new"), received = 1234)
            forceCache.update(cached, updated)
            updated.close()
        }

        forceCache.get(request)!!.use {
            assertEquals("new", it.header("X-Version"))
            assertEquals(1234L, it.receivedResponseAtMillis)
            assertEquals("body", it.body.string())
        }
    }

    @Test
    fun cachedResponseClosesSafelyWhenCacheClosesBeforeDeferredMetadataUpdate() {
        val request = request()
        store(request, "body")
        val cached = forceCache.get(request)!!
        val updated = response(request, "unused".toResponseBody(), headersOf("X-Version", "new"))
        forceCache.update(cached, updated)
        updated.close()

        // Windows 上更新需等正文读流关闭；缓存先关闭时，释放响应不得再次编辑已关闭缓存。
        forceCache.close()
        cached.close()

        assertTrue(forceCache.isClosed)
    }

    @Test
    fun updatingClosedCacheSafelySkipsMetadataWrite() {
        val request = request()
        store(request, "body")
        val cached = forceCache.get(request)!!
        val updated = response(request, "unused".toResponseBody(), headersOf("X-Version", "new"))
        forceCache.close()

        forceCache.update(cached, updated)
        cached.close()
        updated.close()

        // 重开后仍是关闭前的完整条目，关闭缓存期间没有写入新的元数据。
        httpCache = Cache(forceCache.directory, 1024L * 1024)
        forceCache = ForceCache(OkHttpUtils.diskLruCache(httpCache))
        forceCache.get(request)!!.use {
            assertNull(it.header("X-Version"))
            assertEquals("body", it.body.string())
        }
    }

    @Test
    fun malformedTimestampIsCacheMissRatherThanRuntimeException() {
        val request = request()
        store(request, "original")
        val editor = forceCache.cache.edit(ForceCache.key(request))!!
        editor.newSink(0).buffer().use {
            it.writeUtf8("${request.url}\nGET\n0\nHTTP/1.1 200 OK\n2\nOkHttp-Sent-Millis: invalid\nOkHttp-Received-Millis: 1\n")
        }
        editor.commit()

        assertNull(forceCache.get(request))
        store(request, "repaired")
        assertEquals("repaired", cachedBody(request))
    }

    @Test
    fun httpsHandshakeAndLegacyAbsentCertificateListsAreReadable() {
        val request = request("https://example.com/tls")
        val handshake = Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, emptyList(), emptyList())
        forceCache.put(response(request, "tls".toResponseBody()).newBuilder().handshake(handshake).build()).use { it.body.string() }
        forceCache.get(request)!!.use {
            assertEquals(TlsVersion.TLS_1_3, it.handshake!!.tlsVersion)
            assertEquals("tls", it.body.string())
        }
        val editor = forceCache.cache.edit(ForceCache.key(request))!!
        editor.newSink(0).buffer().use {
            // 保留早期 OkHttp 中 -1 证书链的兼容读取能力。
            it.writeUtf8("${request.url}\nGET\n0\nHTTP/1.1 200 OK\n0\n\nTLS_AES_128_GCM_SHA256\n-1\n-1\nTLSv1.3\n")
        }
        editor.commit()
        assertEquals("tls", cachedBody(request))
    }

    private fun request(url: String = "http://example.com/resource") = Request.Builder().url(url).build()

    private fun store(request: Request, value: String) {
        forceCache.put(response(request, value.toResponseBody())).use { assertEquals(value, it.body.string()) }
    }

    private fun cachedBody(request: Request): String? = forceCache.get(request)?.use { it.body.string() }

    private fun response(
        request: Request,
        body: ResponseBody,
        headers: Headers = Headers.Builder().build(),
        sent: Long = System.currentTimeMillis(),
        received: Long = sent
    ) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .headers(headers)
        .body(body)
        .sentRequestAtMillis(sent)
        .receivedResponseAtMillis(received)
        .build()

    private fun body(source: Source) = object : ResponseBody() {
        private val bufferedSource = source.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = bufferedSource
    }
}
