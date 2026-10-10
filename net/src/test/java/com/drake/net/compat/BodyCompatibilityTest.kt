package com.drake.net.compat

import android.app.Application
import com.drake.net.body.NetRequestBody
import com.drake.net.body.NetResponseBody
import com.drake.net.body.peekBytes
import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import com.drake.net.interceptor.LogRecordInterceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** 使用真实 Okio 缓冲层验证包装器契约，避免仅针对实现细节编写断言。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class BodyCompatibilityTest {

    @Test
    fun requestWrapperPreservesOneShotDuplexAndMetadata() {
        val original = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = 12L
            override fun isOneShot() = true
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) = Unit
        }
        val wrapped = NetRequestBody(original)

        assertTrue(wrapped.isOneShot())
        assertTrue(wrapped.isDuplex())
        assertEquals(original.contentType(), wrapped.contentType())
        assertEquals(12L, wrapped.contentLength())
    }

    @Test
    fun uploadFlushesRemainingBytesWithoutClosingOkHttpSink() {
        val target = TrackingSink()
        NetRequestBody("payload".toRequestBody()).writeTo(target.buffer())

        assertEquals("payload", target.bytes.readUtf8())
        assertTrue(target.flushCount > 0)
        assertFalse("请求体不得关闭由 OkHttp 持有的输出流", target.closed)
    }

    @Test
    fun uploadPropagatesFlushFailureAndDoesNotReportSuccess() {
        val failure = IOException("flush failed")
        val progress = ProgressRecorder()
        val target = object : ForwardingSink(Buffer()) {
            override fun flush() = throw failure
        }
        val wrapped = NetRequestBody(unknownLengthBody("payload"), listeners(progress))

        assertSame(failure, assertThrows(IOException::class.java) { wrapped.writeTo(target.buffer()) })
        assertTrue(progress.events.none { it.finished })
    }

    @Test
    fun knownLengthUploadDoesNotReportSuccessBeforeFailedFlush() {
        val progress = ProgressRecorder()
        val target = object : ForwardingSink(Buffer()) {
            override fun flush() = throw IOException("flush failed")
        }

        assertThrows(IOException::class.java) {
            NetRequestBody("payload".toRequestBody(), listeners(progress)).writeTo(target.buffer())
        }
        assertTrue(progress.events.none { it.finished })
    }

    @Test
    fun uploadPropagatesBodyWriteFailureWithoutCompletingProgress() {
        val failure = IOException("source failed")
        val progress = ProgressRecorder()
        val original = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun writeTo(sink: BufferedSink) = throw failure
        }

        assertSame(failure, assertThrows(IOException::class.java) {
            NetRequestBody(original, listeners(progress)).writeTo(TrackingSink().buffer())
        })
        assertTrue(progress.events.isEmpty())
    }

    @Test
    fun duplexBodyCanContinueWritingAfterWriteToReturns() {
        lateinit var retainedSink: BufferedSink
        val original = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) { retainedSink = sink }
        }
        val target = TrackingSink()
        val progress = ProgressRecorder()
        NetRequestBody(original, listeners(progress)).writeTo(target.buffer())

        assertEquals(0, target.flushCount)
        assertFalse(target.closed)
        assertTrue(progress.events.isEmpty())
        retainedSink.writeUtf8("after-return")
        retainedSink.flush()
        assertEquals("after-return", target.bytes.readUtf8())
    }

    @Test
    fun unknownLengthDuplexCloseCompletesEveryListenerExactlyOnce() {
        lateinit var retainedSink: BufferedSink
        val original = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) { retainedSink = sink }
        }
        val target = TrackingSink()
        val first = ProgressRecorder(interval = Long.MAX_VALUE)
        val second = ProgressRecorder(interval = Long.MAX_VALUE)
        NetRequestBody(original, listeners(first, second)).writeTo(target.buffer())
        retainedSink.writeUtf8("payload")
        assertTrue(first.events.isEmpty())
        assertFalse(target.closed)
        retainedSink.close()
        retainedSink.close()

        assertTrue(target.closed)
        assertEquals("payload", target.bytes.readUtf8())
        for (recorder in listOf(first, second)) {
            assertEquals(listOf(ProgressSnapshot(7L, -1L, true)), recorder.events)
        }
    }

    @Test
    fun duplexCloseFailureIsPreservedWithoutReportingUploadSuccess() {
        // 同时检查已知和未知长度；双工上传只有关闭成功才算完成，不能在写够字节时提前完成。
        for (length in listOf(-1L, 7L)) {
            lateinit var retainedSink: BufferedSink
            val original = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = length
                override fun isDuplex() = true
                override fun writeTo(sink: BufferedSink) { retainedSink = sink }
            }
            val failure = IOException("duplex close failed")
            val target = object : ForwardingSink(Buffer()) {
                override fun close() = throw failure
            }
            val progress = ProgressRecorder()
            NetRequestBody(original, listeners(progress)).writeTo(target.buffer())
            retainedSink.writeUtf8("payload")
            assertSame(failure, assertThrows(IOException::class.java) { retainedSink.close() })
            assertTrue(progress.events.none { it.finished })
        }
    }

    @Test
    fun unknownLengthDuplexBufferedWriteFailureDuringCloseDoesNotReportSuccess() {
        lateinit var retainedSink: BufferedSink
        val original = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) { retainedSink = sink }
        }
        val failure = IOException("duplex write failed")
        val target = object : ForwardingSink(Buffer()) {
            override fun write(source: Buffer, byteCount: Long) = throw failure
        }
        val progress = ProgressRecorder()
        NetRequestBody(original, listeners(progress)).writeTo(target.buffer())
        retainedSink.writeUtf8("payload")
        // 小正文留在 Okio 缓冲区中，直到 close 才真实写入，因此需要单独覆盖此失败路径。
        assertSame(failure, assertThrows(IOException::class.java) { retainedSink.close() })
        assertTrue(progress.events.none { it.finished })
    }

    @Test
    fun allUploadListenersReceiveFinalProgressForEachReplay() {
        val first = ProgressRecorder()
        val second = ProgressRecorder()
        val wrapped = NetRequestBody("payload".toRequestBody(), listeners(first, second))
        repeat(2) { wrapped.writeTo(TrackingSink().buffer()) }

        for (recorder in listOf(first, second)) {
            assertEquals(2, recorder.events.count { it.finished })
            assertTrue(recorder.events.filter { it.finished }.all { it.current == 7L && it.total == 7L })
        }
    }

    @Test
    fun unknownLengthUploadReportsFinalByteCountEvenWhenIntervalHasNotElapsed() {
        val progress = ProgressRecorder(interval = Long.MAX_VALUE)
        NetRequestBody(unknownLengthBody("payload"), listeners(progress)).writeTo(TrackingSink().buffer())

        assertEquals(listOf(ProgressSnapshot(7L, -1L, true)), progress.events)
    }

    @Test
    fun allDownloadListenersReceiveFinalProgress() {
        val first = ProgressRecorder()
        val second = ProgressRecorder()
        val wrapped = NetResponseBody(responseBody("payload"), listeners(first, second))
        assertEquals("payload", wrapped.string())

        for (recorder in listOf(first, second)) {
            assertEquals(1, recorder.events.count { it.finished })
            assertEquals(ProgressSnapshot(7L, 7L, true), recorder.events.last())
        }
    }

    @Test
    fun unknownLengthDownloadCompletesAtEndOfStream() {
        val progress = ProgressRecorder(interval = Long.MAX_VALUE)
        val completeCount = AtomicInteger()
        val wrapped = NetResponseBody(responseBody("payload", length = -1L), listeners(progress)) {
            completeCount.incrementAndGet()
        }

        assertEquals("payload", wrapped.string())
        assertEquals(listOf(ProgressSnapshot(7L, -1L, true)), progress.events)
        assertEquals(1, completeCount.get())
    }

    @Test
    fun responseCompletionRunsExactlyOnceAcrossEofAndRepeatedClose() {
        val completeCount = AtomicInteger()
        val wrapped = NetResponseBody(responseBody("payload")) { completeCount.incrementAndGet() }
        val source = wrapped.source()
        assertEquals("payload", source.readUtf8())
        assertTrue(source.exhausted())
        source.close()
        wrapped.close()

        assertEquals(1, completeCount.get())
    }

    @Test
    fun earlyResponseCloseCompletesAndClosesTheUnderlyingSource() {
        val completeCount = AtomicInteger()
        var closed = false
        val source = object : ForwardingSource(Buffer().writeUtf8("payload")) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        val wrapped = NetResponseBody(responseBody(source, 7L)) { completeCount.incrementAndGet() }
        wrapped.close()
        wrapped.close()

        assertTrue(closed)
        assertEquals(1, completeCount.get())
    }

    @Test
    fun responseReadFailureIsPreservedAndCompletesOnce() {
        val completeCount = AtomicInteger()
        val failure = IOException("read failed")
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
        }.buffer()
        val wrapped = NetResponseBody(responseBody(source, -1L)) { completeCount.incrementAndGet() }

        assertSame(failure, assertThrows(IOException::class.java) { wrapped.source().readByte() })
        wrapped.close()
        assertEquals(1, completeCount.get())
    }

    @Test
    fun responseSourceCloseFailureIsPreservedAndStillCompletes() {
        val completeCount = AtomicInteger()
        val failure = IOException("close failed")
        val source = object : ForwardingSource(Buffer()) {
            override fun close() = throw failure
        }.buffer()
        val wrapped = NetResponseBody(responseBody(source, -1L)) { completeCount.incrementAndGet() }

        // ResponseBody.close 按 OkHttp 契约吞掉 IOException，source.close 则必须保留异常。
        assertSame(failure, assertThrows(IOException::class.java) { wrapped.source().close() })
        assertEquals(1, completeCount.get())
    }

    @Test
    fun requestLogsNeverReadOneShotOrDuplexBodies() {
        val logger = object : LogRecordInterceptor(true) {
            fun requestLog(request: Request) = getRequestLog(request)
        }
        for (duplex in listOf(false, true)) {
            val writes = AtomicInteger()
            val body = object : RequestBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun isOneShot() = !duplex
                override fun isDuplex() = duplex
                override fun writeTo(sink: BufferedSink) { writes.incrementAndGet(); sink.writeUtf8("payload") }
            }
            val log = logger.requestLog(Request.Builder().url("https://localhost/upload").post(body).build())

            assertNotNull(log)
            assertEquals(0, writes.get())
            assertFalse(log!!.contains("payload"))
        }
    }

    @Test
    fun unlimitedResponsePeekReturnsFullBodyWithoutConsumingResponse() {
        val expected = "payload-".repeat(3000)
        val wrapped = NetResponseBody(responseBody(expected))

        assertEquals(expected, wrapped.peekBytes(-1).utf8())
        assertEquals("pay", wrapped.peekBytes(3).utf8())
        assertEquals(expected, wrapped.string())
    }

    private fun unknownLengthBody(value: String) = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun writeTo(sink: BufferedSink) { sink.writeUtf8(value) }
    }

    private fun responseBody(value: String, length: Long = value.toByteArray().size.toLong()) =
        responseBody(Buffer().writeUtf8(value), length)

    private fun responseBody(source: BufferedSource, length: Long) = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun source() = source
    }

    private fun listeners(vararg progress: ProgressRecorder) =
        ConcurrentLinkedQueue<ProgressListener>().apply { addAll(progress.toList()) }

    private class TrackingSink : ForwardingSink(Buffer()) {
        val bytes: Buffer get() = delegate as Buffer
        var closed = false
        var flushCount = 0
        override fun flush() { flushCount++; super.flush() }
        override fun close() { closed = true; super.close() }
    }
}

/** 回调会复用可变 Progress 对象，测试必须复制值才能验证每个阶段的真实结果。 */
internal data class ProgressSnapshot(val current: Long, val total: Long, val finished: Boolean)

internal class ProgressRecorder(interval: Long = 0) : ProgressListener(interval) {
    val events = mutableListOf<ProgressSnapshot>()
    override fun onProgress(p: Progress) { events += ProgressSnapshot(p.currentByteCount, p.totalByteCount, p.finish) }
}
