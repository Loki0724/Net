package com.drake.net.sample.converter

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONReader
import com.alibaba.fastjson2.reader.ObjectReaderCreator
import com.alibaba.fastjson2.reader.ObjectReaderProvider
import com.drake.net.convert.JSONConvert
import org.json.JSONObject
import java.lang.reflect.Type

class FastJsonConverter : JSONConvert(code = "errorCode", message = "errorMsg", success = "0") {
    companion object {
        // 显式使用公开反射读取器，让 JVM 测试和 Android 运行时采用一致的字段解析方式。
        // 独立 provider 只影响该转换器，并缓存读取器供多次请求复用。
        private val readerProvider = ObjectReaderProvider(ObjectReaderCreator.INSTANCE)
    }

    override fun <R> String.parseBody(succeed: Type): R? {
        val string = try {
            JSONObject(this).getString("data")
        } catch (e: Exception) {
            this
        }
        // 按字段解析 Kotlin 数据类，同时保留无参构造器设置的默认值；每次解析使用独立上下文。
        val context = JSONReader.Context(readerProvider, JSONReader.Feature.FieldBased)
        return JSON.parseObject(string, succeed, context)
    }
}
