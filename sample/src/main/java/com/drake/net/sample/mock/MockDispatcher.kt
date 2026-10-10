package com.drake.net.sample.mock

import android.util.Log
import com.drake.engine.base.app
import com.drake.net.sample.R
import com.drake.net.sample.constants.Api
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MockDispatcher : Dispatcher() {

    companion object {
        fun initialize() {
            val srv = MockWebServer()
            srv.dispatcher = MockDispatcher()
            thread {
                try {
                    srv.start(8091)
                } catch (e: Exception) {
                    Log.e("日志", "MOCK服务启动失败", e)
                }
            }
        }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.url.encodedPath) {
            Api.TEXT -> getString("Request Success : ${request.method}")
            Api.DELAY -> textResponse("Request Success : ${request.method}")
                .bodyDelay(2, TimeUnit.SECONDS).build()
            Api.UPLOAD -> uploadFile(request)
            Api.GAME -> getRawResponse(R.raw.game)
            Api.DATA -> getRawResponse(R.raw.data)
            Api.ARRAY -> getRawResponse(R.raw.array)
            Api.USER_INFO -> getRawResponse(R.raw.user)
            Api.CONFIG -> getRawResponse(R.raw.user)
            Api.TIME -> textResponse(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()))
                .bodyDelay(1, TimeUnit.SECONDS).build()
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun getString(text: String): MockResponse {
        return textResponse(text).build()
    }

    // MockWebServer 5 的响应不可变，统一通过 Builder 配置响应头和响应体。
    private fun textResponse(text: String): MockResponse.Builder {
        return MockResponse.Builder().setHeader("Content-Type", "text/plain").body(text)
    }

    // 将接口上传的文件复制到应用缓存目录
    private fun uploadFile(req: RecordedRequest): MockResponse {
        val file = File(app.cacheDir.absolutePath, "uploadFile.apk")
        // RecordedRequest.body 已改为 ByteString；use 保证写入异常时仍释放文件资源。
        file.sink().buffer().use { sink ->
            req.body?.let(sink::write)
        }
        return getString("Upload success")
    }

    private fun getRawResponse(rawId: Int, delay: Long = 500): MockResponse {
        val buf = app.resources.openRawResource(rawId).source().buffer().use { it.readUtf8() }
        return MockResponse.Builder()
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .bodyDelay(delay, TimeUnit.MILLISECONDS)
            .body(buf)
            .build()
    }
}
