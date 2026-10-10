/*
 * MIT License
 *
 * Copyright (c) 2023 劉強東 https://github.com/liangjingkanji
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.drake.net.body

import android.os.SystemClock
import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.*
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

class NetRequestBody(
    private val body: RequestBody,
    private val progressListeners: ConcurrentLinkedQueue<ProgressListener>? = null
) : RequestBody() {

    private val contentLength by lazy { body.contentLength() }

    override fun contentType(): MediaType? {
        return body.contentType()
    }

    @Throws(IOException::class)
    override fun contentLength(): Long {
        return contentLength
    }

    // 保留原请求体的发送语义，避免 OkHttp 对一次性流重放或将双工请求误当成普通上传。
    override fun isOneShot(): Boolean = body.isOneShot()

    override fun isDuplex(): Boolean = body.isDuplex()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        if (sink is Buffer ||
            sink.toString().contains("com.android.tools.profiler.support.network.HttpTracker\$OutputStreamTracker")) {
            body.writeTo(sink)
        } else {
            // 每次发送创建独立的进度状态，可重复发送的请求体在重试时重新计算上传进度。
            val progress = Progress()
            val bufferedSink: BufferedSink = sink.toProgress(progress).buffer()
            body.writeTo(bufferedSink)
            // sink 由 OkHttp 管理生命周期；这里只刷新普通请求的缓冲区，不能提前关闭连接。
            // 双工请求允许 writeTo 返回后继续写入，刷新和完成通知由后续写入负责。
            if (isDuplex()) return
            bufferedSink.flush()
            completeProgress(progress)
        }
    }

    // 普通上传刷新成功或双工发送方关闭输出流成功后，所有监听器都只收到一次完成通知。
    private fun completeProgress(progress: Progress) {
        if (progress.finish) return
        progress.finish = true
        progress.totalByteCount = contentLength
        val currentElapsedTime = SystemClock.elapsedRealtime()
        progressListeners?.forEach { progressListener ->
            progress.intervalByteCount = progressListener.intervalByteCount
            progress.intervalTime = currentElapsedTime - progressListener.elapsedTime
            progressListener.onProgress(progress)
            progressListener.elapsedTime = currentElapsedTime
            progressListener.intervalByteCount = 0L
        }
    }

    private fun Sink.toProgress(progress: Progress) = object : ForwardingSink(this) {
        private var writeByteCount = 0L
        private var failed = false

        @Throws(IOException::class)
        override fun write(source: Buffer, byteCount: Long) {
            try {
                super.write(source, byteCount)
            } catch (e: Throwable) {
                failed = true
                throw e
            }
            writeByteCount += byteCount
            progress.currentByteCount = writeByteCount
            progress.totalByteCount = contentLength
            if (!progressListeners.isNullOrEmpty()) {
                val currentElapsedTime = SystemClock.elapsedRealtime()
                // 写够 Content-Length 只表示字节已交给输出流，刷新或关闭失败仍不能报告成功。
                val reachedLength = writeByteCount == contentLength
                progressListeners.forEach { progressListener ->
                    progressListener.intervalByteCount += byteCount
                    val currentInterval = currentElapsedTime - progressListener.elapsedTime
                    if (!progress.finish && (reachedLength || currentInterval >= progressListener.interval)) {
                        progressListener.onProgress(
                            progress.apply {
                                currentByteCount = writeByteCount
                                totalByteCount = contentLength
                                intervalByteCount = progressListener.intervalByteCount
                                intervalTime = currentInterval
                            }
                        )
                        progressListener.elapsedTime = currentElapsedTime
                        progressListener.intervalByteCount = 0L
                    }
                }
            }
        }

        override fun close() {
            // 双工请求由发送方关闭保留的 sink，这也是未知长度正文唯一可靠的发送结束信号。
            // 先执行底层关闭，异常会原样传播，失败时不会错误地显示上传完成。
            try {
                super.close()
            } catch (e: Throwable) {
                failed = true
                throw e
            }
            // Okio 在写出缓冲区失败后仍会尝试 close，成功关闭不能覆盖先前的发送失败。
            if (isDuplex() && !failed) completeProgress(progress)
        }

        override fun flush() {
            try {
                super.flush()
            } catch (e: Throwable) {
                failed = true
                throw e
            }
        }
    }
}
