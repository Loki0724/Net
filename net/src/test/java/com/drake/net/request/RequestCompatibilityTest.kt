package com.drake.net.request

import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import com.drake.net.tag.NetTag
import okhttp3.Headers
import okhttp3.OkHttpUtils
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.typeOf

/**
 * 验证 Net 的可变标签、请求头 API 与 OkHttp 5.5 不可变标签集合之间的兼容性。
 * 使用真实 Request/Builder，确保扩展 API 的修改能通过 OkHttp 的公开读取接口观察到。
 */
class RequestCompatibilityTest {

    @Test
    fun emptyBuilderTagsCanBeAddedReadAndRemovedBeforeUrlIsSet() {
        // Builder 尚未设置 URL，读取标签不能通过 build() 实现，否则会在此处异常。
        val builder = Request.Builder()
        val tags = builder.tags()
        assertTrue(tags.isEmpty())
        assertNull(builder.tagOf<String>())

        tags[String::class.java] = "builder-value"
        assertEquals("builder-value", builder.tagOf<String>())
        val request = builder.url("https://example.com/").build()
        assertEquals("builder-value", request.tag(String::class.java))

        assertEquals("builder-value", tags.remove(String::class.java))
        assertNull(builder.tagOf<String>())
        assertEquals("builder-value", request.tag(String::class.java))
    }

    @Test
    fun builderMapViewSeesNativeTagChangesAndTypeKeysRemainSeparate() {
        val builder = newBuilder()
        val tags = builder.tags()
        builder.tag(String::class.java, "string")
        builder.tag(Number::class.java, 123)
        builder.tag(Any::class.java, "object")

        assertEquals("string", tags[String::class.java])
        assertEquals(123, tags[Number::class.java])
        assertEquals("object", tags[Any::class.java])
        assertEquals(setOf(String::class.java, Number::class.java, Any::class.java), tags.keys)
        assertEquals(listOf("string", 123, "object"), tags.values.toList())

        builder.tag(String::class.java, "updated")
        assertEquals("updated", tags[String::class.java])
        builder.tagOf<String>(null)
        assertFalse(tags.containsKey(String::class.java))
        assertEquals(123, builder.tagOf<Number>())
    }

    @Test
    fun kotlinPrimitiveAndJavaBoxedTypeKeysCanBeReadEnumeratedAndRemoved() {
        val builder = newBuilder().tag(Int::class.javaObjectType, 42)
        val tags = builder.tags()
        assertEquals(42, builder.tagOf<Int>())
        assertEquals(42, tags[Int::class.javaObjectType])
        assertTrue(tags.keys.contains(Int::class.javaObjectType))
        val request = builder.build()

        request.tagOf(43)
        assertEquals(43, request.tagOf<Int>())
        assertEquals(43, request.tag(Int::class.javaObjectType))
        request.tags().keys.remove(Int::class.javaObjectType)
        assertNull(request.tagOf<Int>())
    }

    @Test
    fun builtRequestWithNoTagsSupportsNativeReadingAndRemoval() {
        val request = newBuilder().build()
        val tags = request.tags()
        assertTrue(tags.isEmpty())

        assertSame(request, request.tagOf("value"))
        assertEquals("value", request.tag(String::class.java))
        assertEquals("value", tags[String::class.java])
        tags[Number::class.java] = 42
        assertEquals(42, request.tag(Number::class.java))

        assertSame(request, request.tagOf<String>(null))
        assertNull(request.tag(String::class.java))
        assertFalse(tags.containsKey(String::class.java))
        tags.clear()
        assertTrue(tags.isEmpty())
        assertNull(request.tag(Number::class.java))
    }

    @Test
    fun requestMapViewsObserveEachOthersChangesAndPreserveExistingNativeTags() {
        val request = newBuilder()
            .tag(String::class.java, "existing")
            .tag(Number::class.java, 7)
            .build()
        val first = request.tags()
        val second = request.tags()

        assertEquals("existing", first.put(String::class.java, "replacement"))
        assertEquals("replacement", second[String::class.java])
        assertEquals(7, request.tag(Number::class.java))
        second[NetTag.CacheKey::class.java] = NetTag.CacheKey("cache-key")
        assertEquals("cache-key", request.tagOf<NetTag.CacheKey>()?.value)
        assertTrue(first.containsKey(NetTag.CacheKey::class.java))
        assertEquals(3, first.size)
    }

    @Test
    fun tagMapEntriesKeysAndValuesSupportWriteThroughChanges() {
        val request = newBuilder()
            .tag(String::class.java, "first")
            .tag(Number::class.java, 2)
            .tag(Any::class.java, "object")
            .build()
        val tags = request.tags()
        val entry = tags.entries.first { it.key == String::class.java }
        assertEquals("first", entry.setValue("changed"))
        assertEquals("changed", request.tag(String::class.java))

        assertTrue(tags.keys.remove(Number::class.java))
        assertNull(request.tag(Number::class.java))
        assertTrue(tags.values.remove("object"))
        assertNull(request.tag(Any::class.java))

        val iterator = tags.entries.iterator()
        assertEquals(String::class.java, iterator.next().key)
        iterator.remove()
        assertTrue(tags.isEmpty())
        assertNull(request.tag(String::class.java))
    }

    @Test
    fun builderTagMapEntriesWriteBackToTheNativeBuilder() {
        val builder = newBuilder().tag(String::class.java, "initial")
        val tags = builder.tags()
        assertEquals("initial", tags.entries.single().setValue("updated"))
        assertEquals("updated", builder.build().tag(String::class.java))

        val iterator = tags.keys.iterator()
        iterator.next()
        iterator.remove()
        assertNull(builder.build().tag(String::class.java))
        builder.tagOf("again")
        tags.entries.clear()
        assertNull(builder.build().tag(String::class.java))
    }

    @Test
    fun nullMapValuesRemoveNativeTags() {
        val builder = newBuilder().tag(String::class.java, "builder")
        assertEquals("builder", builder.tags().put(String::class.java, null))
        assertNull(builder.tagOf<String>())
        assertTrue(builder.tags().isEmpty())

        val request = builder.tag(String::class.java, "request").build()
        assertEquals("request", request.tags().put(String::class.java, null))
        assertNull(request.tag(String::class.java))
        assertTrue(request.tags().isEmpty())
    }

    @Test
    fun mutableBuilderTagsDoNotChangeAlreadyBuiltRequests() {
        val builder = newBuilder().tag(String::class.java, "original")
        val request = builder.build()
        builder.tags()[String::class.java] = "updated"
        builder.tags()[Number::class.java] = 1

        assertEquals("original", request.tag(String::class.java))
        assertNull(request.tag(Number::class.java))
        assertEquals("updated", builder.build().tag(String::class.java))
    }

    @Test
    fun requestMutationDoesNotChangePreviouslyCreatedBuildersOrRequests() {
        val request = newBuilder().tag(String::class.java, "original").build()
        val previousBuilder = request.newBuilder()
        val previousRequest = previousBuilder.build()

        request.tagOf("updated")
        request.tagOf<Number>(10)
        assertEquals("updated", request.newBuilder().build().tag(String::class.java))
        assertEquals(10, request.newBuilder().build().tag(Number::class.java))
        assertEquals("original", previousBuilder.build().tag(String::class.java))
        assertNull(previousBuilder.build().tag(Number::class.java))
        assertEquals("original", previousRequest.tag(String::class.java))

        previousBuilder.tags()[String::class.java] = "independent"
        assertEquals("updated", request.tag(String::class.java))
        assertEquals("original", previousRequest.tag(String::class.java))
    }

    @Test
    fun requestMetadataAndExtrasRemainReadableByOkHttp() {
        val builder = newBuilder()
        builder.id = "request-id"
        builder.group = "request-group"
        builder.kType = typeOf<List<String>>()
        builder.setExtra("source", "builder")
        builder.setExtra("nullable", null)
        val request = builder.build()

        assertEquals("request-id", request.id)
        assertEquals("request-group", request.group)
        assertEquals(typeOf<List<String>>(), request.kType)
        assertEquals("builder", request.extra("source"))
        assertFalse(request.extras().containsKey("nullable"))
        assertSame(builder.extras(), request.extras())

        request.id = "changed-id"
        request.group = null
        request.kType = typeOf<String>()
        request.extras()["source"] = "request"
        assertEquals("changed-id", request.tag(NetTag.RequestId::class.java)?.value)
        assertNull(request.tag(NetTag.RequestGroup::class.java))
        assertEquals(typeOf<String>(), request.tag(NetTag.RequestKType::class.java)?.value)
        assertEquals("request", request.extra("source"))
        assertEquals("request-id", builder.id)
        assertEquals("request-group", builder.group)
        assertEquals(typeOf<List<String>>(), builder.kType)
    }

    @Test
    fun extrasCanBeAddedToAnInitiallyUntaggedRequest() {
        val request = newBuilder().build()
        request.extras()["new"] = "value"
        assertEquals("value", request.extra("new"))
        assertSame(request.extras(), request.tag(NetTag.Extras::class.java))
        assertEquals("value", request.newBuilder().build().extra("new"))
    }

    @Test
    fun progressListenerContainersRemainStableAndTyped() {
        val listener = object : ProgressListener() {
            override fun onProgress(p: Progress) = Unit
        }
        val builder = newBuilder()
        builder.uploadListeners().add(listener)
        builder.downloadListeners().add(listener)
        val request = builder.build()

        assertSame(builder.uploadListeners(), request.uploadListeners())
        assertSame(builder.downloadListeners(), request.downloadListeners())
        assertSame(listener, request.tag(NetTag.UploadListeners::class.java)?.single())
        assertSame(listener, request.tag(NetTag.DownloadListeners::class.java)?.single())

        val fresh = newBuilder().build()
        fresh.uploadListeners().add(listener)
        fresh.downloadListeners().add(listener)
        assertSame(listener, fresh.tag(NetTag.UploadListeners::class.java)?.single())
        assertSame(listener, fresh.tag(NetTag.DownloadListeners::class.java)?.single())
    }

    @Test
    fun mutableHeadersAreWrittenToBuildAndRemainIndependentAfterBuild() {
        val builder = newBuilder()
        val headers = builder.headers()
        headers.add("X-Repeat", "first")
        builder.addHeader("X-Repeat", "second")
        headers.set("X-Value", "before")
        assertSame(headers, builder.headers())
        val first = builder.build()

        headers.set("X-Value", "after")
        headers.removeAll("X-Repeat")
        assertEquals(listOf("first", "second"), first.headers("X-Repeat"))
        assertEquals("before", first.header("X-Value"))
        assertTrue(builder.build().headers("X-Repeat").isEmpty())
        assertEquals("after", builder.build().header("X-Value"))
    }

    @Test
    fun replacingHeadersAndDerivedBuilderHeadersRemainMutable() {
        val builder = newBuilder()
        val staleHeaders = builder.headers()
        builder.headers(Headers.Builder().add("X-New", "new").build())
        staleHeaders.add("X-Stale", "ignored")
        builder.headers().add("X-Added", "added")
        val request = builder.build()
        val derived = request.newBuilder()
        derived.headers().set("X-New", "changed")

        assertNull(request.header("X-Stale"))
        assertEquals("added", request.header("X-Added"))
        assertEquals("new", request.header("X-New"))
        assertEquals("changed", derived.build().header("X-New"))
    }

    @Test
    fun lenientCacheHeaderParsingPreservesLegacyHeaderValues() {
        val headers = Headers.Builder()
        OkHttpUtils.addLenient(headers, "X-Name: first:second")
        OkHttpUtils.addLenient(headers, "X-Name: 中文")
        OkHttpUtils.addLenient(headers, ": unnamed")
        OkHttpUtils.addLenient(headers, "no-colon")

        assertEquals(listOf("first:second", "中文"), headers.build().values("X-Name"))
        assertEquals(listOf("unnamed", "no-colon"), headers.build().values(""))
    }

    private fun newBuilder() = Request.Builder().url("https://example.com/")
}
