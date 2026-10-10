@file:Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")

package com.drake.net.sample.converter

import com.drake.net.convert.NetConverter
import com.drake.net.exception.ConvertException
import com.drake.net.exception.RequestParamsException
import com.drake.net.exception.ServerResponseException
import com.drake.net.request.kType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import okhttp3.Response
import java.lang.reflect.Type

// 新版 Protobuf 运行库仍将编解码 API 标为实验性，显式声明此示例使用该契约。
@OptIn(ExperimentalSerializationApi::class)
class ProtobufConverter : NetConverter {

    override fun <R> onConvert(succeed: Type, response: Response): R? {
        try {
            return NetConverter.onConvert<R>(succeed, response)
        } catch (e: ConvertException) {
            val code = response.code
            when {
                code in 200..299 -> { // 请求成功
                    val bytes = response.body.bytes()
                    val kType = response.request.kType ?: throw ConvertException(response, "Request does not contain KType")
                    return ProtoBuf.decodeFromByteArray(ProtoBuf.serializersModule.serializer(kType), bytes) as R
                }
                code in 400..499 -> throw RequestParamsException(response, code.toString()) // 请求参数错误
                code >= 500 -> throw ServerResponseException(response, code.toString()) // 服务器异常错误
                else -> throw ConvertException(response, message = "Http status code not within range")
            }
        }
    }
}
