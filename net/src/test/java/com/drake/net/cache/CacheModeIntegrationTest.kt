package com.drake.net.cache

import com.drake.net.NetConfig
import com.drake.net.exception.NoCacheException
import com.drake.net.interceptor.NetOkHttpInterceptor
import com.drake.net.tag.NetTag
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.OkHttpUtils
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 使用真实拦截器和本地 HTTP 服务验证四种强制缓存模式及标准 HTTP 缓存共存。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CacheModeIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var httpCache: Cache
    private lateinit var forceCache: ForceCache
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpCache = Cache(temporaryFolder.newFolder("cache"), 1024L * 1024)
        forceCache = ForceCache(OkHttpUtils.diskLruCache(httpCache))
        client = OkHttpClient.Builder()
            .cache(httpCache)
            .addInterceptor(NetOkHttpInterceptor)
            .retryOnConnectionFailure(false)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        NetConfig.runningCalls.clear()
    }

    @After
    fun tearDown() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        httpCache.close()
        server.close()
        NetConfig.runningCalls.clear()
    }

    @Test
    fun readReturnsCachedResponseWithoutNetworkAndMissingEntryFails() {
        val request = request(CacheMode.READ, "/missing")
        assertThrows(NoCacheException::class.java) { client.newCall(request).execute().close() }
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse(body = "saved"))
        assertEquals("saved", execute(request(CacheMode.WRITE)))
        client.newCall(request(CacheMode.READ)).execute().use {
            assertEquals("saved", it.body.string())
            assertNotNull(it.cacheResponse)
            assertNull(it.networkResponse)
        }
        assertEquals(1, server.requestCount)
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun writeAlwaysUsesNetworkAndReplacesExistingCache() {
        server.enqueue(MockResponse(body = "first"))
        server.enqueue(MockResponse(body = "second"))

        assertEquals("first", execute(request(CacheMode.WRITE)))
        assertEquals("second", execute(request(CacheMode.WRITE)))
        assertEquals("second", execute(request(CacheMode.READ)))
        assertEquals(2, server.requestCount)
        val networkRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertTrue(networkRequest.headers["Cache-Control"]!!.contains("no-store"))
    }

    @Test
    fun readThenRequestUsesNetworkOnlyOnCacheMiss() {
        server.enqueue(MockResponse(body = "downloaded"))

        assertEquals("downloaded", execute(request(CacheMode.READ_THEN_REQUEST)))
        assertEquals("downloaded", execute(request(CacheMode.READ_THEN_REQUEST)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun requestThenReadRefreshesCacheAndFallsBackWhenServerGoesOffline() {
        server.enqueue(MockResponse(body = "first"))
        server.enqueue(MockResponse(body = "fresh"))
        assertEquals("first", execute(request(CacheMode.WRITE)))
        assertEquals("fresh", execute(request(CacheMode.REQUEST_THEN_READ)))
        assertEquals(2, server.requestCount)
        val fallback = request(CacheMode.REQUEST_THEN_READ)
        // 关闭监听端口并清空连接池，保证接下来的请求是真实连接失败而非复用旧连接。
        server.close()
        client.connectionPool.evictAll()

        assertEquals("fresh", execute(fallback))
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun postAndGetHaveIndependentPersistentCacheEntries() {
        server.enqueue(MockResponse(body = "get-result"))
        server.enqueue(MockResponse(body = "post-result"))
        assertEquals("get-result", execute(request(CacheMode.WRITE)))
        assertEquals("post-result", execute(request(CacheMode.WRITE).newBuilder().post("payload".toRequestBody()).build()))
        assertEquals("get-result", execute(request(CacheMode.READ)))
        assertEquals("post-result", execute(request(CacheMode.READ).newBuilder().post("different payload".toRequestBody()).build()))
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun expiredReadThenRequestFetchesAndReplacesEntry() {
        server.enqueue(MockResponse(body = "stale"))
        server.enqueue(MockResponse(body = "fresh"))
        assertEquals("stale", execute(request(CacheMode.WRITE)))
        val staleEntry = request(CacheMode.READ).newBuilder()
            .tag(NetTag.CacheValidTime::class.java, NetTag.CacheValidTime(-1))
            .build()
        assertThrows(NoCacheException::class.java) { client.newCall(staleEntry).execute().close() }
        val refresh = staleEntry.newBuilder().tag(CacheMode::class.java, CacheMode.READ_THEN_REQUEST).build()

        assertEquals("fresh", execute(refresh))
        assertEquals("fresh", execute(request(CacheMode.READ)))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun standardHttpCacheAndForceCacheShareOneStoreWithoutOverwritingEachOther() {
        server.enqueue(MockResponse.Builder().body("http-cached").addHeader("Cache-Control", "max-age=3600").build())
        val standardRequest = request(CacheMode.WRITE, "/standard").newBuilder()
            .tag(CacheMode::class.java, null)
            .build()
        assertEquals("http-cached", execute(standardRequest))
        client.newCall(standardRequest).execute().use {
            assertEquals("http-cached", it.body.string())
            assertNotNull(it.cacheResponse)
            assertNull(it.networkResponse)
        }
        server.enqueue(MockResponse(body = "forced"))
        assertEquals("forced", execute(request(CacheMode.WRITE, "/forced")))
        assertEquals("forced", execute(request(CacheMode.READ, "/forced")))
        assertEquals("http-cached", execute(standardRequest))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun httpsResponseStoresPeerCertificatesAndCanBeReadWhenServerIsClosed() {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCertificate.certificate)
            .build()
        // 当前测试服务器改为 HTTPS，客户端只信任本测试生成的证书，验证真实 TLS 握手写入缓存。
        server.useHttps(serverCertificates.sslSocketFactory())
        client = client.newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        server.enqueue(MockResponse(body = "secure"))
        val request = request(CacheMode.WRITE, "/secure")

        assertEquals("secure", execute(request))
        server.close()
        client.connectionPool.evictAll()
        forceCache.flush()
        httpCache.close()
        httpCache = Cache(forceCache.directory, 1024L * 1024)
        forceCache = ForceCache(OkHttpUtils.diskLruCache(httpCache))
        client = client.newBuilder().cache(httpCache).build()

        client.newCall(request(CacheMode.READ, "/secure")).execute().use {
            assertEquals("secure", it.body.string())
            assertNotNull(it.handshake)
            assertEquals(1, it.handshake!!.peerCertificates.size)
            assertEquals(heldCertificate.certificate, it.handshake!!.peerCertificates.single())
            assertNotNull(it.cacheResponse)
            assertNull(it.networkResponse)
        }
    }

    @Test
    fun untrustedTlsCertificateFailsWithoutWritingCache() {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.enqueue(MockResponse(body = "must-not-be-cached"))
        val request = request(CacheMode.WRITE, "/untrusted")

        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
        assertNull(forceCache.get(request))
        assertEquals(0, forceCache.writeSuccessCount())
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    private fun request(mode: CacheMode, path: String = "/resource") = Request.Builder()
        .url(server.url(path))
        .tag(ForceCache::class.java, forceCache)
        .tag(CacheMode::class.java, mode)
        .build()

    private fun execute(request: Request): String = client.newCall(request).execute().use { it.body.string() }
}
