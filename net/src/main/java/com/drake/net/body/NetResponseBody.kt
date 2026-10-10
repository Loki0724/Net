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
import okhttp3.ResponseBody
import okio.*
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class NetResponseBody(
    private val body: ResponseBody,
    private val progressListeners: ConcurrentLinkedQueue<ProgressListener>? = null,
    private val complete: (() -> Unit)? = null
) : ResponseBody() {

    private val progress = Progress()
    private val bufferedSource by lazy { body.source().toProgress().buffer() }
    private val contentLength by lazy { body.contentLength() }
    private val completed = AtomicBoolean(false)

    // 读完、异常和主动关闭都结束请求；多个终止路径只能通知一次，避免重复清理请求队列。
    private fun completeOnce() {
        if (completed.compareAndSet(false, true)) complete?.invoke()
    }

    override fun contentType(): MediaType? {
        return body.contentType()
    }

    override fun contentLength(): Long {
        return contentLength
    }

    override fun source(): BufferedSource {
        return bufferedSource
    }

    private fun Source.toProgress() = object : ForwardingSource(this) {
        private var readByteCount: Long = 0

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            try {
                val bytesRead = super.read(sink, byteCount)
                // 即使监听器稍后才添加，也要保留已经读取的字节数供后续进度使用。
                readByteCount += if (bytesRead != -1L) bytesRead else 0
                if (!progressListeners.isNullOrEmpty()) {
                    val currentElapsedTime = SystemClock.elapsedRealtime()
                    val finished = readByteCount == contentLength || bytesRead == -1L
                    val alreadyFinished = progress.finish
                    progressListeners.forEach { progressListener ->
                        progressListener.intervalByteCount += if (bytesRead != -1L) bytesRead else 0
                        val currentInterval = currentElapsedTime - progressListener.elapsedTime
                        if (!alreadyFinished && (finished || currentInterval >= progressListener.interval)) {
                            progressListener.onProgress(
                                progress.apply {
                                    currentByteCount = readByteCount
                                    totalByteCount = contentLength
                                    intervalByteCount = progressListener.intervalByteCount
                                    intervalTime = currentInterval
                                    finish = finished
                                }
                            )
                            progressListener.elapsedTime = currentElapsedTime
                            progressListener.intervalByteCount = 0L
                        }
                    }
                    progress.finish = finished
                }
                if (bytesRead == -1L) completeOnce()
                return bytesRead
            } catch (e: Exception) {
                completeOnce()
                throw e
            }
        }

        override fun close() {
            try {
                super.close()
            } finally {
                // 调用方可能只读取响应头或部分正文，关闭时也必须释放运行中的请求记录。
                completeOnce()
            }
        }
    }
}
