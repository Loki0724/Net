package com.drake.net.sample

import android.app.Application
import com.drake.net.NetConfig
import com.drake.net.exception.ResponseException
import com.drake.net.exception.ServerResponseException
import com.drake.net.reflect.typeTokenOf
import com.drake.net.request.kType
import com.drake.net.sample.converter.FastJsonConverter
import com.drake.net.sample.converter.GsonConverter
import com.drake.net.sample.converter.MoshiConverter
import com.drake.net.sample.converter.ProtobufConverter
import com.drake.net.sample.converter.SerializationConverter
import com.drake.net.sample.model.UserInfoModel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.reflect.typeOf

/** 使用真实序列化实现验证升级后泛型、默认值和业务错误的转换契约。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28], application = Application::class)
class ConverterCompatibilityTest {
    @Before
    fun initializeContext() {
        // 测试使用普通 Application，需手动提供业务错误缺省文案所依赖的应用上下文。
        NetConfig.app = RuntimeEnvironment.getApplication()
    }

    @Test
    fun serializationPreservesDefaultsAndIgnoresUnknownFields() {
        response<UserInfoModel>("""{"userId":7,"username":null,"extra":"ignored"}""".toResponseBody()).use {
            val model = SerializationConverter().onConvert<UserInfoModel>(typeTokenOf<UserInfoModel>(), it)!!
            assertEquals(7, model.userId)
            assertEquals("", model.username)
            assertEquals(0, model.age)
        }
    }

    @Test
    fun serializationHandlesNestedGenericBusinessPayload() {
        val json = """{"errorCode":"0","data":{"users":[{"userId":9,"username":"alice"}]}}"""
        response<Map<String, List<UserInfoModel>>>(json.toResponseBody()).use {
            val result = SerializationConverter().onConvert<Map<String, List<UserInfoModel>>>(
                typeTokenOf<Map<String, List<UserInfoModel>>>(), it
            )!!
            assertEquals("alice", result.getValue("users").single().username)
        }
    }

    @Test
    fun serializationRetainsBusinessErrorCodeAndMessage() {
        response<UserInfoModel>("""{"errorCode":"40101","errorMsg":"token expired"}""".toResponseBody()).use {
            val error = assertThrows(ResponseException::class.java) {
                SerializationConverter().onConvert<UserInfoModel>(typeTokenOf<UserInfoModel>(), it)
            }
            assertEquals("40101", error.tag)
            assertEquals("token expired", error.message)
        }
    }

    @Test
    fun serializationRetainsServerErrorClassification() {
        response<UserInfoModel>("unavailable".toResponseBody(), 503).use {
            assertThrows(ServerResponseException::class.java) {
                SerializationConverter().onConvert<UserInfoModel>(typeTokenOf<UserInfoModel>(), it)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun protobufRoundTripsGeneratedKotlinSerializer() {
        val model = UserInfoModel(3, "protobuf", 25, "12.50")
        val bytes = ProtoBuf.encodeToByteArray(serializer<UserInfoModel>(), model)
        response<UserInfoModel>(bytes.toResponseBody()).use {
            val result = ProtobufConverter().onConvert<UserInfoModel>(typeTokenOf<UserInfoModel>(), it)
            assertEquals(model, result)
        }
    }

    @Test
    fun fastjson2ParsesGenericPayloadThroughUpdatedApi() {
        val json = """{"data":[{"userId":12,"username":"fastjson"}]}"""
        val converter = FastJsonConverter()
        val result = with(converter) { json.parseBody<List<UserInfoModel>>(typeTokenOf<List<UserInfoModel>>()) }!!
        assertEquals(12, result.single().userId)
        assertEquals("fastjson", result.single().username)
        assertEquals(0, result.single().age)
        assertEquals("", result.single().balance)
    }

    @Test
    fun fastjson2ParsesNestedAndDirectKotlinPayloads() {
        val converter = FastJsonConverter()
        with(converter) {
            // 同一个 provider 连续读取不同泛型形状，均不能丢失字段或覆盖 Kotlin 默认值。
            val direct = """{"userId":15,"username":"direct"}"""
                .parseBody<UserInfoModel>(typeTokenOf<UserInfoModel>())!!
            val nested = """{"users":[{"userId":16,"username":"nested"}]}"""
                .parseBody<Map<String, List<UserInfoModel>>>(typeTokenOf<Map<String, List<UserInfoModel>>>())!!
            assertEquals(15, direct.userId)
            assertEquals("direct", direct.username)
            assertEquals("", direct.balance)
            assertEquals(16, nested.getValue("users").single().userId)
            assertEquals("nested", nested.getValue("users").single().username)
        }
    }

    @Test
    fun gsonParsesGenericBusinessPayload() {
        val json = """{"data":[{"userId":13,"username":"gson"}]}"""
        val converter = GsonConverter()
        val result = with(converter) { json.parseBody<List<UserInfoModel>>(typeTokenOf<List<UserInfoModel>>()) }!!
        assertEquals("gson", result.single().username)
    }

    @Test
    fun moshiPreservesKotlinDefaultsInGenericPayload() {
        val json = """[{"userId":14,"username":"moshi"}]"""
        val converter = MoshiConverter()
        val result = with(converter) { json.parseBody<List<UserInfoModel>>(typeTokenOf<List<UserInfoModel>>()) }!!
        assertEquals("moshi", result.single().username)
        assertEquals(0, result.single().age)
        assertEquals("", result.single().balance)
    }

    private inline fun <reified T> response(body: ResponseBody, code: Int = 200): Response {
        // 序列化转换器通过请求标签读取 KType，测试同时覆盖新 OkHttp 的标签读取。
        val builder = Request.Builder().url("https://example.com/")
        builder.kType = typeOf<T>()
        return Response.Builder().request(builder.build()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body).build()
    }
}
