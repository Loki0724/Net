package com.drake.net.compat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.life
import androidx.lifecycle.scopeLife
import androidx.lifecycle.scopeNetLife
import com.drake.net.NetConfig
import com.drake.net.cookie.PersistentCookieJar
import com.drake.net.request.group
import com.drake.net.time.Interval
import com.drake.net.time.IntervalStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 使用真实 Android SQLite 与新版 AndroidX，在最低 API 及常见 API 上验证兼容性。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [21, 28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CookieLifecycleCompatibilityTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private val openJars = mutableListOf<PersistentCookieJar>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Config.NONE 不执行 AndroidX Startup，OkHttp 5 的域名 Cookie 解析需显式初始化资产上下文。
        OkHttp.initialize(context)
        // Windows 的原生 SQLite 受路径长度限制，Robolectric 沙箱目录已包含测试类名。
        dbName = "ck_${UUID.randomUUID().toString().take(8)}"
        context.getDatabasePath(dbName).parentFile!!.mkdirs()
        NetConfig.runningCalls.clear()
    }

    @After
    fun tearDown() {
        openJars.forEach { it.close() }
        context.deleteDatabase(dbName)
        NetConfig.runningCalls.clear()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun persistentAndSessionCookiesSurviveClosingAndReopeningSQLite() {
        val url = "https://example.com/".toHttpUrl()
        val jar = newJar()
        jar.saveFromResponse(url, listOf(
            cookie("persistent", expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)),
            cookie("session")
        ))
        jar.close()
        val reopened = newJar()

        assertEquals(setOf("persistent", "session"), reopened.loadForRequest(url).map { it.name }.toSet())
        assertTrue(reopened.loadForRequest(url).first { it.name == "persistent" }.persistent)
        assertFalse(reopened.loadForRequest(url).first { it.name == "session" }.persistent)
    }

    @Test
    fun sameNameCookiesWithDifferentPathsCoexistAndFollowPathBoundaries() {
        val jar = newJar()
        val url = "https://example.com/private/item".toHttpUrl()
        jar.saveFromResponse(url, listOf(
            cookie("token", value = "root", path = "/"),
            cookie("token", value = "private", path = "/private")
        ))

        assertEquals(listOf("private", "root"), jar.loadForRequest(url).map { it.value })
        assertEquals(listOf("root"), jar.loadForRequest("https://example.com/public".toHttpUrl()).map { it.value })
        assertEquals(listOf("root"), jar.loadForRequest("https://example.com/private-other".toHttpUrl()).map { it.value })
        assertEquals(2, jar.getAll(url).size)
    }

    @Test
    fun domainCookiesReachSubdomainsWhileHostOnlyCookiesRemainOnTheirHost() {
        val jar = newJar()
        val url = "https://example.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(
            cookie("host-only"),
            cookie("domain", hostOnly = false)
        ))

        assertEquals(setOf("host-only", "domain"), jar.loadForRequest(url).map { it.name }.toSet())
        assertEquals(listOf("domain"), jar.loadForRequest("https://sub.example.com/".toHttpUrl()).map { it.name })
        assertTrue(jar.loadForRequest("https://notexample.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://example.com.evil.invalid/".toHttpUrl()).isEmpty())
    }

    @Test
    fun secureCookiesAreNeverSentOverHttp() {
        val jar = newJar()
        val secure = cookie("secure", secure = true)
        jar.saveFromResponse("https://example.com/".toHttpUrl(), listOf(secure, cookie("plain")))

        assertEquals(setOf("secure", "plain"), jar.loadForRequest("https://example.com/".toHttpUrl()).map { it.name }.toSet())
        assertEquals(listOf("plain"), jar.loadForRequest("http://example.com/".toHttpUrl()).map { it.name })
    }

    @Test
    fun expiredSetCookieDeletesOnlyTheMatchingNameDomainAndPath() {
        val jar = newJar()
        val url = "https://example.com/private".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("token", path = "/"), cookie("token", path = "/private")))
        val deletion = Cookie.parse(url, "token=deleted; Max-Age=0; Path=/private")!!
        jar.saveFromResponse(url, listOf(deletion))

        assertEquals(listOf("/"), jar.loadForRequest(url).map { it.path })
        assertEquals(1, storedCount())
    }

    @Test
    fun expiredStoredCookiesAreRemovedWhenRead() {
        val url = "https://example.com/".toHttpUrl()
        val jar = newJar()
        jar.addAll(url, listOf(cookie("token")))
        // 直接写入过期文本，验证重启后读取旧数据库也会清理过期数据。
        SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE cookies SET cookie = ?", arrayOf("token=old; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"))
        }

        assertTrue(jar.loadForRequest(url).isEmpty())
        assertEquals(0, storedCount())
    }

    @Test
    fun oldSchemaMigratesDomainPathAndSessionCookiesWithoutLosingValidValues() {
        val file = context.getDatabasePath(dbName)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE cookies (url TEXT, name TEXT, cookie TEXT, PRIMARY KEY(url, name))")
            db.execSQL("INSERT INTO cookies VALUES (?, ?, ?)",
                arrayOf("www.example.com", "legacy-domain", "legacy-domain=value; Domain=example.com; Path=/private"))
            db.execSQL("INSERT INTO cookies VALUES (?, ?, ?)",
                arrayOf("www.example.com", "legacy-host", "legacy-host=value; Path=/"))
            db.execSQL("INSERT INTO cookies VALUES (?, ?, ?)",
                arrayOf("www.example.com", "expired", "expired=old; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"))
            db.version = 1
        }
        val jar = newJar()

        assertEquals(setOf("legacy-domain", "legacy-host"),
            jar.loadForRequest("https://www.example.com/private".toHttpUrl()).map { it.name }.toSet())
        assertEquals(listOf("legacy-domain"),
            jar.loadForRequest("https://other.example.com/private".toHttpUrl()).map { it.name })
        assertEquals(listOf("legacy-host"),
            jar.loadForRequest("https://www.example.com/public".toHttpUrl()).map { it.name })
        assertFalse(jar.getAll("https://www.example.com/".toHttpUrl()).first { it.name == "legacy-host" }.persistent)
        assertEquals(2, storedCount())

        jar.addAll("https://example.com/".toHttpUrl(), listOf(cookie("legacy-domain", path = "/", hostOnly = false)))
        assertEquals(2, jar.loadForRequest("https://other.example.com/private".toHttpUrl()).size)
    }

    @Test
    fun removeByNameDomainAndClearRemainCompatible() {
        val jar = newJar()
        val url = "https://example.com/".toHttpUrl()
        jar.addAll(url, listOf(cookie("token", path = "/"), cookie("token", path = "/private"), cookie("keep")))
        jar.remove(url, "token")
        assertEquals(listOf("keep"), jar.getAll(url).map { it.name })
        jar.remove(url)
        assertTrue(jar.getAll(url).isEmpty())
        jar.addAll(url, listOf(cookie("again")))
        jar.clear()
        assertEquals(0, storedCount())
    }

    @Test
    fun subdomainRemovalIncludesParentDomainCookiesAndPreservesHostOnlySiblings() {
        val jar = newJar()
        val subdomain = "https://sub.example.com/".toHttpUrl()
        val sibling = "https://sibling.example.com/".toHttpUrl()
        val parent = "https://example.com/".toHttpUrl()
        val siblingCookie = Cookie.Builder().name("token").value("sibling")
            .hostOnlyDomain(sibling.host).path("/").build()
        jar.addAll(subdomain, listOf(
            cookie("token", value = "parent-root", hostOnly = false),
            cookie("token", value = "parent-private", path = "/private", hostOnly = false),
            cookie("keep", hostOnly = false)
        ))
        jar.addAll(parent, listOf(cookie("parent-only")))
        jar.addAll(sibling, listOf(siblingCookie))
        assertEquals(setOf("token", "keep"), jar.getAll(subdomain).map { it.name }.toSet())

        jar.remove(subdomain, "token")
        assertEquals(listOf("keep"), jar.getAll(subdomain).map { it.name })
        assertEquals("sibling", jar.getAll(sibling).first { it.name == "token" }.value)
        assertEquals(setOf("keep", "parent-only"), jar.getAll(parent).map { it.name }.toSet())

        jar.remove(subdomain)
        assertTrue(jar.getAll(subdomain).isEmpty())
        assertEquals(listOf("token"), jar.getAll(sibling).map { it.name })
        assertEquals(listOf("parent-only"), jar.getAll(parent).map { it.name })
    }

    @Test
    fun realOkHttpRoundTripSavesSendsAndDeletesCookiesThroughSQLite() {
        val server = MockWebServer()
        server.start()
        val jar = newJar()
        val client = OkHttpClient.Builder().cookieJar(jar).build()
        try {
            server.enqueue(MockResponse.Builder().addHeader("Set-Cookie", "token=value; Path=/private; Max-Age=3600").body("set").build())
            server.enqueue(MockResponse.Builder().body("private").build())
            server.enqueue(MockResponse.Builder().body("public").build())
            server.enqueue(MockResponse.Builder().addHeader("Set-Cookie", "token=; Path=/private; Max-Age=0").body("delete").build())
            server.enqueue(MockResponse.Builder().body("empty").build())

            listOf("/private", "/private/item", "/public", "/private", "/private").forEach { path ->
                client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.body.string() }
            }
            assertNull(server.takeRequest().headers["Cookie"])
            assertEquals("token=value", server.takeRequest().headers["Cookie"])
            assertNull(server.takeRequest().headers["Cookie"])
            assertEquals("token=value", server.takeRequest().headers["Cookie"])
            assertNull(server.takeRequest().headers["Cookie"])
            assertEquals(0, storedCount())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }

    @Test
    fun viewModelStoreClearCancelsAllLifeScopesAndRunsTheirFinallyBlocks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val model = object : ViewModel() {}
        val store = ViewModelStore().apply { put("model", model) }
        var completed = 0
        val first = model.scopeLife(dispatcher) {
            try { awaitCancellation() } finally { completed++ }
        }
        val second = model.scopeLife(dispatcher) {
            try { awaitCancellation() } finally { completed++ }
        }
        runCurrent()
        assertTrue(first.coroutineContext[Job]!!.isActive)
        assertTrue(second.coroutineContext[Job]!!.isActive)

        store.clear()
        runCurrent()
        assertTrue(first.coroutineContext[Job]!!.isCancelled)
        assertTrue(second.coroutineContext[Job]!!.isCancelled)
        assertEquals(2, completed)
    }

    @Test
    fun viewModelStoreClearCancelsNetScopeAndItsGroupedOkHttpCall() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val model = object : ViewModel() {}
        val store = ViewModelStore().apply { put("model", model) }
        val scope = model.scopeNetLife(dispatcher) { awaitCancellation() }
        val request = Request.Builder().url("https://example.com/").apply { group = scope.scopeGroup }.build()
        val call = NetConfig.okHttpClient.newCall(request)
        NetConfig.runningCalls.add(WeakReference(call))
        runCurrent()

        store.clear()
        runCurrent()
        assertTrue(scope.coroutineContext[Job]!!.isCancelled)
        assertTrue(call.isCanceled())
        assertTrue(NetConfig.runningCalls.isEmpty())
    }

    @Test
    fun viewModelStoreClearCancelsIntervalAndItsIndependentTickerProducer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val model = object : ViewModel() {}
        val store = ViewModelStore().apply { put("model", model) }
        var finishCalls = 0
        val interval = Interval(1, TimeUnit.DAYS, initialDelay = 1)
            .life(model)
            .finish { finishCalls++ }
            .start()
        runCurrent()
        assertEquals(IntervalStatus.STATE_ACTIVE, interval.state)
        val ticker = Interval::class.java.getDeclaredField("ticker").apply { isAccessible = true }
            .get(interval) as ReceiveChannel<*>

        store.clear()
        runCurrent()
        assertEquals(IntervalStatus.STATE_IDLE, interval.state)
        assertTrue(ticker.isClosedForReceive)
        assertEquals(0, finishCalls)
        assertEquals(0L, interval.count)
    }

    private fun newJar() = PersistentCookieJar(context, dbName).also { openJars.add(it) }

    private fun storedCount(): Int = SQLiteDatabase.openDatabase(
        context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
    ).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM cookies", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun cookie(
        name: String,
        value: String = "value",
        path: String = "/",
        hostOnly: Boolean = true,
        secure: Boolean = false,
        expiresAt: Long? = null
    ): Cookie = Cookie.Builder().name(name).value(value).path(path).apply {
        if (hostOnly) hostOnlyDomain("example.com") else domain("example.com")
        if (secure) secure()
        expiresAt?.let { expiresAt(it) }
    }.build()
}
