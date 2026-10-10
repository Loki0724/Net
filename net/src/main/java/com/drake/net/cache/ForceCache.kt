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
package com.drake.net.cache

import com.drake.net.request.tagOf
import com.drake.net.tag.NetTag
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.internal.cache.DiskLruCache
import okhttp3.internal.http.StatusLine
import okio.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.File
import java.io.Flushable
import java.io.IOException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Caches HTTP and HTTPS responses to the filesystem so they may be reused, saving time and
 * bandwidth.
 *
 * ## Cache Optimization
 *
 * To measure cache effectiveness, this class tracks three statistics:
 *
 *  * **[Request Count:][requestCount]** the number of HTTP requests issued since this cache was
 *    created.
 *  * **[Network Count:][networkCount]** the number of those requests that required network use.
 *  * **[Hit Count:][hitCount]** the number of those requests whose responses were served by the
 *    cache.
 *
 * Sometimes a request will result in a conditional cache hit. If the cache contains a stale copy of
 * the response, the client will issue a conditional `GET`. The server will then send either
 * the updated response if it has changed, or a short 'not modified' response if the client's copy
 * is still valid. Such responses increment both the network count and hit count.
 *
 * The best way to improve the cache hit rate is by configuring the web server to return cacheable
 * responses. Although this client honors all [HTTP/1.1 (RFC 7234)][rfc_7234] cache headers, it
 * doesn't cache partial responses.
 *
 * ## Force a Network Response
 *
 * In some situations, such as after a user clicks a 'refresh' button, it may be necessary to skip
 * the cache, and fetch data directly from the server. To force a full refresh, add the `no-cache`
 * directive:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder().noCache().build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * If it is only necessary to force a cached response to be validated by the server, use the more
 * efficient `max-age=0` directive instead:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxAge(0, TimeUnit.SECONDS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * ## Force a Cache Response
 *
 * Sometimes you'll want to show resources if they are available immediately, but not otherwise.
 * This can be used so your application can show *something* while waiting for the latest data to be
 * downloaded. To restrict a request to locally-cached resources, add the `only-if-cached`
 * directive:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .onlyIfCached()
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * Response forceCacheResponse = client.newCall(request).execute();
 * if (forceCacheResponse.code() != 504) {
 *   // The resource was cached! Show it.
 * } else {
 *   // The resource was not cached.
 * }
 * ```
 *
 * This technique works even better in situations where a stale response is better than no response.
 * To permit stale cached responses, use the `max-stale` directive with the maximum staleness in
 * seconds:
 *
 * ```
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxStale(365, TimeUnit.DAYS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build();
 * ```
 *
 * The [CacheControl] class can configure request caching directives and parse response caching
 * directives. It even offers convenient constants [CacheControl.FORCE_NETWORK] and
 * [CacheControl.FORCE_CACHE] that address the use cases above.
 *
 * [rfc_7234]: http://tools.ietf.org/html/rfc7234
 */
class ForceCache internal constructor(
    val cache: DiskLruCache
) : Closeable, Flushable {

    // read and write statistics, all guarded by 'this'.
    internal var writeSuccessCount = 0
    internal var writeAbortCount = 0

    val isClosed: Boolean
        get() = cache.isClosed()

    internal fun get(request: Request): Response? {
        val snapshot: DiskLruCache.Snapshot = try {
            cache[key(request)] ?: return null
        } catch (_: IOException) {
            return null // Give up because the cache cannot be read.
        }

        val entry: Entry = try {
            Entry(snapshot.getSource(ENTRY_METADATA))
        } catch (_: IOException) {
            snapshot.closeQuietly()
            return null
        }

        // 使用调用方的请求构造缓存响应，保留标签和请求体；自定义缓存键允许不同请求共享条目。
        val response = entry.response(snapshot, request)
        val value = request.tagOf<NetTag.CacheValidTime>()?.value
        return if (value != null && System.currentTimeMillis() - response.receivedResponseAtMillis > value) {
            // 过期响应不会交给调用方，必须在此释放快照，否则 Windows 上会阻止条目更新与删除。
            response.close()
            null
        } else {
            response
        }
    }

    internal fun put(response: Response): Response {
        if (!response.isSuccessful) return response
        // HTTPS 的磁盘元数据需要 TLS 握手；人工构造且没有握手的响应不能按此格式持久化。
        if (response.request.url.isHttps && response.handshake == null) return response
        val entry = Entry(response)
        var editor: DiskLruCache.Editor? = null
        val cacheRequest: RealCacheRequest? = try {
            editor = cache.edit(key(response.request)) ?: return response
            entry.writeTo(editor)
            RealCacheRequest(editor)
        } catch (_: IOException) {
            abortQuietly(editor)
            null
        }
        cacheRequest ?: return response
        val cacheBody = cacheRequest.body().buffer()
        // OkHttp 5 的响应体始终非空，无内容响应会提供空响应体，同样需要正常提交缓存事务。
        val responseBody = response.body
        val source = responseBody.source()
        val cacheWritingSource = object : Source {
            private var cacheRequestClosed = false

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                try {
                    val bytesRead = source.read(sink, byteCount)
                    if (bytesRead == -1L) {
                        if (!cacheRequestClosed) {
                            cacheRequestClosed = true
                            cacheBody.close()
                        }
                        return -1
                    }

                    sink.copyTo(cacheBody.buffer, sink.size - bytesRead, bytesRead)
                    cacheBody.emitCompleteSegments()
                    return bytesRead
                } catch (e: IOException) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true
                        // 网络读取或缓存写入中断后都废弃事务，避免部分正文被当成完整缓存。
                        cacheRequest.abort()
                    }
                    throw e
                }
            }

            override fun timeout() = source.timeout()

            @Throws(IOException::class)
            override fun close() {
                if (!cacheRequestClosed &&
                    !discardRemaining()) {
                    cacheRequestClosed = true
                    cacheRequest.abort()
                }
                source.close()
            }
        }
        val contentType = response.header("Content-Type")
        val contentLength = responseBody.contentLength()
        return response.newBuilder()
            .body(object : ResponseBody() {
                private val bufferedSource = cacheWritingSource.buffer()

                override fun contentType(): MediaType? = contentType?.toMediaTypeOrNull()

                override fun contentLength(): Long = contentLength

                override fun source(): BufferedSource = bufferedSource
            })
            .build()
    }

    @Throws(IOException::class)
    internal fun remove(request: Request) {
        cache.remove(key(request))
    }

    internal fun update(cached: Response, network: Response) {
        val entry = Entry(network)
        val body = cached.body as? CacheResponseBody ?: return
        val snapshot = body.snapshot
        var editor: DiskLruCache.Editor? = null
        try {
            editor = editIfOpen(snapshot)
            if (editor == null) {
                // Windows 不允许编辑仍有打开读流的条目。正文关闭后再更新，保留正在读取的正文。
                // snapshot.edit 仍校验原序列号，因此不会覆盖期间已被其他请求替换的条目。
                body.afterClose {
                    var deferredEditor: DiskLruCache.Editor? = null
                    try {
                        deferredEditor = editIfOpen(snapshot) ?: return@afterClose
                        entry.writeTo(deferredEditor)
                        deferredEditor.commit()
                    } catch (_: IOException) {
                        abortQuietly(deferredEditor)
                    }
                }
                return
            }
            entry.writeTo(editor)
            editor.commit()
        } catch (_: IOException) {
            abortQuietly(editor)
        }
    }

    /**
     * 与 DiskLruCache.close 使用相同锁，避免检查完成后缓存被关闭、编辑器抛出状态异常。
     * 仍通过快照编辑，保留序列号校验，已关闭或已被替换的缓存都直接放弃元数据更新。
     */
    private fun editIfOpen(snapshot: DiskLruCache.Snapshot): DiskLruCache.Editor? = synchronized(cache) {
        if (cache.isClosed()) null else snapshot.edit()
    }

    private fun abortQuietly(editor: DiskLruCache.Editor?) {
        // Give up because the cache cannot be written.
        try {
            editor?.abort()
        } catch (_: IOException) {
        }
    }

    /**
     * Initialize the cache. This will include reading the journal files from the storage and building
     * up the necessary in-memory cache information.
     *
     * The initialization time may vary depending on the journal file size and the current actual
     * cache size. The application needs to be aware of calling this function during the
     * initialization phase and preferably in a background worker thread.
     *
     * Note that if the application chooses to not call this method to initialize the cache. By
     * default, OkHttp will perform lazy initialization upon the first usage of the cache.
     */
    @Throws(IOException::class)
    fun initialize() {
        cache.initialize()
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete all files in the cache
     * directory including files that weren't created by the cache.
     */
    @Throws(IOException::class)
    fun delete() {
        cache.delete()
    }

    /**
     * Deletes all values stored in the cache. In-flight writes to the cache will complete normally,
     * but the corresponding responses will not be stored.
     */
    @Throws(IOException::class)
    fun evictAll() {
        cache.evictAll()
    }

    /**
     * Returns an iterator over the URLs in this cache. This iterator doesn't throw
     * `ConcurrentModificationException`, but if new responses are added while iterating, their URLs
     * will not be returned. If existing responses are evicted during iteration, they will be absent
     * (unless they were already returned).
     *
     * The iterator supports [MutableIterator.remove]. Removing a URL from the iterator evicts the
     * corresponding response from the cache. Use this to evict selected responses.
     */
    @Throws(IOException::class)
    fun urls(): MutableIterator<String> {
        return object : MutableIterator<String> {
            private val delegate: MutableIterator<DiskLruCache.Snapshot> = cache.snapshots()
            private var nextUrl: String? = null
            private var canRemove = false

            override fun hasNext(): Boolean {
                if (nextUrl != null) return true

                canRemove = false // Prevent delegate.remove() on the wrong item!
                while (delegate.hasNext()) {
                    try {
                        delegate.next().use { snapshot ->
                            val metadata = snapshot.getSource(ENTRY_METADATA).buffer()
                            nextUrl = metadata.readUtf8LineStrict()
                            return true
                        }
                    } catch (_: IOException) {
                        // We couldn't read the metadata for this snapshot; possibly because the host filesystem
                        // has disappeared! Skip it.
                    }
                }

                return false
            }

            override fun next(): String {
                if (!hasNext()) throw NoSuchElementException()
                val result = nextUrl!!
                nextUrl = null
                canRemove = true
                return result
            }

            override fun remove() {
                check(canRemove) { "remove() before next()" }
                delegate.remove()
            }
        }
    }

    @Synchronized
    fun writeAbortCount(): Int = writeAbortCount

    @Synchronized
    fun writeSuccessCount(): Int = writeSuccessCount

    @Throws(IOException::class)
    fun size(): Long = cache.size()

    /** Max size of the cache (in bytes). */
    fun maxSize(): Long = cache.maxSize

    @Throws(IOException::class)
    override fun flush() {
        cache.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        cache.close()
    }

    @get:JvmName("directory")
    val directory: File
        get() = cache.directory.toFile()

    private inner class RealCacheRequest(
        private val editor: DiskLruCache.Editor
    ) {
        private val cacheOut: Sink = editor.newSink(ENTRY_BODY)
        private val body: Sink
        var done = false

        init {
            this.body = object : ForwardingSink(cacheOut) {
                @Throws(IOException::class)
                override fun close() {
                    synchronized(this@ForceCache) {
                        if (done) return
                        done = true
                    }
                    try {
                        super.close()
                        editor.commit()
                        synchronized(this@ForceCache) {
                            writeSuccessCount++
                        }
                    } catch (e: IOException) {
                        // 提交失败不能计为成功，且必须释放编辑器，允许下次网络响应重新写入。
                        synchronized(this@ForceCache) {
                            writeAbortCount++
                        }
                        abortQuietly(editor)
                        throw e
                    }
                }
            }
        }

        fun abort() {
            synchronized(this@ForceCache) {
                if (done) return
                done = true
                writeAbortCount++
            }
            cacheOut.closeQuietly()
            try {
                editor.abort()
            } catch (_: IOException) {
            }
        }

        fun body(): Sink = body
    }

    private class Entry {
        private val url: String
        private val varyHeaders: Headers
        private val requestMethod: String
        private val protocol: Protocol
        private val code: Int
        private val message: String
        private val responseHeaders: Headers
        private val handshake: Handshake?
        private val sentRequestMillis: Long
        private val receivedResponseMillis: Long

        private val isHttps: Boolean get() = url.startsWith("https://")

        /**
         * Reads an entry from an input stream. A typical entry looks like this:
         *
         * ```
         * http://google.com/foo
         * GET
         * 2
         * Accept-Language: fr-CA
         * Accept-Charset: UTF-8
         * HTTP/1.1 200 OK
         * 3
         * Content-Type: image/png
         * Content-Length: 100
         * Cache-Control: max-age=600
         * ```
         *
         * A typical HTTPS file looks like this:
         *
         * ```
         * https://google.com/foo
         * GET
         * 2
         * Accept-Language: fr-CA
         * Accept-Charset: UTF-8
         * HTTP/1.1 200 OK
         * 3
         * Content-Type: image/png
         * Content-Length: 100
         * Cache-Control: max-age=600
         *
         * AES_256_WITH_MD5
         * 2
         * base64-encoded peerCertificate[0]
         * base64-encoded peerCertificate[1]
         * -1
         * TLSv1.2
         * ```
         *
         * The file is newline separated. The first two lines are the URL and the request method. Next
         * is the number of HTTP Vary request header lines, followed by those lines.
         *
         * Next is the response status line, followed by the number of HTTP response header lines,
         * followed by those lines.
         *
         * HTTPS responses also contain SSL session information. This begins with a blank line, and then
         * a line containing the cipher suite. Next is the length of the peer certificate chain. These
         * certificates are base64-encoded and appear each on their own line. The next line contains the
         * length of the local certificate chain. These certificates are also base64-encoded and appear
         * each on their own line. A length of -1 is used to encode a null array. The last line is
         * optional. If present, it contains the TLS version.
         */
        @Throws(IOException::class)
        constructor(rawSource: Source) {
            try {
                val source = rawSource.buffer()
                url = source.readUtf8LineStrict()
                requestMethod = source.readUtf8LineStrict()
                val varyHeadersBuilder = Headers.Builder()
                val varyRequestHeaderLineCount = readInt(source)
                for (i in 0 until varyRequestHeaderLineCount) {
                    OkHttpUtils.addLenient(varyHeadersBuilder, source.readUtf8LineStrict())
                }
                varyHeaders = varyHeadersBuilder.build()

                val statusLine = StatusLine.parse(source.readUtf8LineStrict())
                protocol = statusLine.protocol
                code = statusLine.code
                if (code < 0) throw IOException("Invalid cached status code: $code")
                message = statusLine.message
                val responseHeadersBuilder = Headers.Builder()
                val responseHeaderLineCount = readInt(source)
                for (i in 0 until responseHeaderLineCount) {
                    OkHttpUtils.addLenient(responseHeadersBuilder, source.readUtf8LineStrict())
                }
                val sendRequestMillisString = responseHeadersBuilder[SENT_MILLIS]
                val receivedResponseMillisString = responseHeadersBuilder[RECEIVED_MILLIS]
                responseHeadersBuilder.removeAll(SENT_MILLIS)
                responseHeadersBuilder.removeAll(RECEIVED_MILLIS)
                sentRequestMillis = readMillis(sendRequestMillisString)
                receivedResponseMillis = readMillis(receivedResponseMillisString)
                responseHeaders = responseHeadersBuilder.build()

                if (isHttps) {
                    val blank = source.readUtf8LineStrict()
                    if (blank.isNotEmpty()) {
                        throw IOException("expected \"\" but was \"$blank\"")
                    }
                    val cipherSuiteString = source.readUtf8LineStrict()
                    val cipherSuite = CipherSuite.forJavaName(cipherSuiteString)
                    val peerCertificates = readCertificateList(source)
                    val localCertificates = readCertificateList(source)
                    val tlsVersion = try {
                        if (!source.exhausted()) {
                            TlsVersion.forJavaName(source.readUtf8LineStrict())
                        } else {
                            TlsVersion.SSL_3_0
                        }
                    } catch (e: IllegalArgumentException) {
                        throw IOException("Invalid cached TLS version", e)
                    }
                    handshake = Handshake.get(tlsVersion, cipherSuite, peerCertificates, localCertificates)
                } else {
                    handshake = null
                }
            } finally {
                rawSource.close()
            }
        }

        constructor(response: Response) {
            this.url = response.request.url.toString()
            this.varyHeaders = response.varyHeaders()
            this.requestMethod = response.request.method
            this.protocol = response.protocol
            this.code = response.code
            this.message = response.message
            this.responseHeaders = response.headers
            this.handshake = response.handshake
            this.sentRequestMillis = response.sentRequestAtMillis
            this.receivedResponseMillis = response.receivedResponseAtMillis
        }

        @Throws(IOException::class)
        fun writeTo(editor: DiskLruCache.Editor) {
            editor.newSink(ENTRY_METADATA).buffer().use { sink ->
                sink.writeUtf8(url).writeByte('\n'.toInt())
                sink.writeUtf8(requestMethod).writeByte('\n'.toInt())
                sink.writeDecimalLong(varyHeaders.size.toLong()).writeByte('\n'.toInt())
                for (i in 0 until varyHeaders.size) {
                    sink.writeUtf8(varyHeaders.name(i))
                        .writeUtf8(": ")
                        .writeUtf8(varyHeaders.value(i))
                        .writeByte('\n'.toInt())
                }

                sink.writeUtf8(StatusLine(protocol, code, message).toString()).writeByte('\n'.toInt())
                sink.writeDecimalLong((responseHeaders.size + 2).toLong()).writeByte('\n'.toInt())
                for (i in 0 until responseHeaders.size) {
                    sink.writeUtf8(responseHeaders.name(i))
                        .writeUtf8(": ")
                        .writeUtf8(responseHeaders.value(i))
                        .writeByte('\n'.toInt())
                }
                sink.writeUtf8(SENT_MILLIS)
                    .writeUtf8(": ")
                    .writeDecimalLong(sentRequestMillis)
                    .writeByte('\n'.toInt())
                sink.writeUtf8(RECEIVED_MILLIS)
                    .writeUtf8(": ")
                    .writeDecimalLong(receivedResponseMillis)
                    .writeByte('\n'.toInt())

                if (isHttps) {
                    sink.writeByte('\n'.toInt())
                    sink.writeUtf8(handshake!!.cipherSuite.javaName).writeByte('\n'.toInt())
                    writeCertList(sink, handshake.peerCertificates)
                    writeCertList(sink, handshake.localCertificates)
                    sink.writeUtf8(handshake.tlsVersion.javaName).writeByte('\n'.toInt())
                }
            }
        }

        @Throws(IOException::class)
        private fun readCertificateList(source: BufferedSource): List<Certificate> {
            // 旧版 OkHttp 使用 -1 表示没有证书，因此这里不能使用只接受非负数的 readInt。
            val length = source.readUtf8LineStrict().toIntOrNull()
                ?: throw IOException("Invalid cached certificate count")
            if (length == -1) return emptyList() // OkHttp v1.2 used -1 to indicate null.
            if (length < 0) throw IOException("Invalid cached certificate count: $length")

            try {
                val certificateFactory = CertificateFactory.getInstance("X.509")
                val result = ArrayList<Certificate>(length)
                for (i in 0 until length) {
                    val line = source.readUtf8LineStrict()
                    val bytes = Buffer()
                    bytes.write(line.decodeBase64() ?: throw IOException("Invalid cached certificate"))
                    result.add(certificateFactory.generateCertificate(bytes.inputStream()))
                }
                return result
            } catch (e: CertificateException) {
                throw IOException(e.message)
            }
        }

        @Throws(IOException::class)
        private fun writeCertList(sink: BufferedSink, certificates: List<Certificate>) {
            try {
                sink.writeDecimalLong(certificates.size.toLong()).writeByte('\n'.toInt())
                for (element in certificates) {
                    val bytes = element.encoded
                    val line = bytes.toByteString().base64()
                    sink.writeUtf8(line).writeByte('\n'.toInt())
                }
            } catch (e: CertificateEncodingException) {
                throw IOException(e.message)
            }
        }

        fun response(snapshot: DiskLruCache.Snapshot, request: Request): Response {
            val contentType = responseHeaders["Content-Type"]
            val contentLength = responseHeaders["Content-Length"]
            val builder = Response.Builder()
                .request(request)
                .protocol(protocol)
                .code(code)
                .message(message)
                .headers(responseHeaders)
                .handshake(handshake)
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(receivedResponseMillis)
            return builder
                .cacheResponse(builder.build())
                .body(CacheResponseBody(snapshot, contentType, contentLength))
                .build()
        }

        companion object {
            // 固定名称与 OkHttp 4/5 的磁盘缓存格式一致，避免依赖 Platform 内部接口。
            private const val SENT_MILLIS = "OkHttp-Sent-Millis"

            private const val RECEIVED_MILLIS = "OkHttp-Received-Millis"

            private fun readMillis(value: String?): Long =
                if (value == null) 0L else value.toLongOrNull()
                    ?: throw IOException("Invalid cached timestamp: $value")
        }
    }

    private class CacheResponseBody(
        val snapshot: DiskLruCache.Snapshot,
        private val contentType: String?,
        private val contentLength: String?
    ) : ResponseBody() {
        private val bodySource: BufferedSource
        private var closed = false
        private var closeAction: (() -> Unit)? = null

        fun afterClose(action: () -> Unit) {
            val alreadyClosed = synchronized(this) {
                if (closed) true else {
                    closeAction = action
                    false
                }
            }
            if (alreadyClosed) action()
        }

        private fun onClosed() {
            val action = synchronized(this) {
                if (closed) return
                closed = true
                closeAction.also { closeAction = null }
            }
            action?.invoke()
        }

        init {
            val source = snapshot.getSource(ENTRY_BODY)
            bodySource = object : ForwardingSource(source) {
                @Throws(IOException::class)
                override fun close() {
                    try {
                        snapshot.close()
                        super.close()
                    } finally {
                        onClosed()
                    }
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = contentType?.toMediaTypeOrNull()

        override fun contentLength(): Long = contentLength?.toLongOrNull() ?: -1L

        override fun source(): BufferedSource = bodySource
    }

    companion object {
        private const val ENTRY_METADATA = 0
        private const val ENTRY_BODY = 1

        /** 缓存读写失败只影响缓存，不应阻止关闭网络响应或释放磁盘快照。 */
        private fun Closeable.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
            }
        }

        /**
         * 提前关闭时最多用 100 毫秒读完剩余响应，使完整响应仍可提交缓存。
         * 临时截止时间不能延长原有网络超时，并须在结束后恢复，避免改变复用连接的后续行为。
         */
        private fun Source.discardRemaining(): Boolean {
            val timeout = timeout()
            val hadDeadline = timeout.hasDeadline()
            val originalDeadline = if (hadDeadline) timeout.deadlineNanoTime() else Long.MAX_VALUE
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)
            timeout.deadlineNanoTime(minOf(originalDeadline, deadline))
            return try {
                val skipped = Buffer()
                while (read(skipped, 8192) != -1L) {
                    skipped.clear()
                }
                true
            } catch (_: IOException) {
                false
            } finally {
                if (hadDeadline) timeout.deadlineNanoTime(originalDeadline) else timeout.clearDeadline()
            }
        }

        @JvmStatic
        fun key(request: Request): String {
            val key = request.tagOf<NetTag.CacheKey>()?.value ?: (request.method + request.url.toString())
            return key.encodeUtf8().sha1().hex()
        }

        @Throws(IOException::class)
        internal fun readInt(source: BufferedSource): Int {
            try {
                val result = source.readDecimalLong()
                val line = source.readUtf8LineStrict()
                if (result < 0L || result > Integer.MAX_VALUE || line.isNotEmpty()) {
                    throw IOException("expected an int but was \"$result$line\"")
                }
                return result.toInt()
            } catch (e: NumberFormatException) {
                throw IOException(e.message)
            }
        }

        /**
         * Returns true if none of the Vary headers have changed between [cachedRequest] and
         * [newRequest].
         */
        fun varyMatches(
            cachedResponse: Response,
            cachedRequest: Headers,
            newRequest: Request
        ): Boolean {
            return cachedResponse.headers.varyFields().none {
                cachedRequest.values(it) != newRequest.headers(it)
            }
        }

        /** Returns true if a Vary header contains an asterisk. Such responses cannot be cached. */
        fun Response.hasVaryAll() = "*" in headers.varyFields()

        /**
         * Returns the names of the request headers that need to be checked for equality when caching.
         */
        private fun Headers.varyFields(): Set<String> {
            var result: MutableSet<String>? = null
            for (i in 0 until size) {
                if (!"Vary".equals(name(i), ignoreCase = true)) {
                    continue
                }

                val value = value(i)
                if (result == null) {
                    result = TreeSet(String.CASE_INSENSITIVE_ORDER)
                }
                for (varyField in value.split(',')) {
                    result.add(varyField.trim())
                }
            }
            return result ?: emptySet()
        }

        /**
         * Returns the subset of the headers in this's request that impact the content of this's body.
         */
        fun Response.varyHeaders(): Headers {
            // Use the request headers sent over the network, since that's what the response varies on.
            // Otherwise OkHttp-supplied headers like "Accept-Encoding: gzip" may be lost.
            val requestHeaders = networkResponse?.request?.headers ?: request.headers
            val responseHeaders = headers
            return varyHeaders(requestHeaders, responseHeaders)
        }

        /**
         * Returns the subset of the headers in [requestHeaders] that impact the content of the
         * response's body.
         */
        private fun varyHeaders(requestHeaders: Headers, responseHeaders: Headers): Headers {
            val varyFields = responseHeaders.varyFields()
            if (varyFields.isEmpty()) return Headers.Builder().build()

            val result = Headers.Builder()
            for (i in 0 until requestHeaders.size) {
                val fieldName = requestHeaders.name(i)
                if (fieldName in varyFields) {
                    result.add(fieldName, requestHeaders.value(i))
                }
            }
            return result.build()
        }
    }
}
