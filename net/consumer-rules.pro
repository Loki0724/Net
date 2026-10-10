# Net 保留历史 Request.tags()/tagOf(value) API，需替换不可变标签字段。
# 禁止优化该字段，避免 R8 将反射修改之前的标签读取消除或常量化。
-keepclassmembers class okhttp3.Request {
    okhttp3.internal.Tags tags;
}

# OkHttp 5.5 无公开标签枚举 API，只对链表节点做精确反射。
-keep class okhttp3.internal.LinkedTags {
    kotlin.reflect.KClass key;
    java.lang.Object value;
    okhttp3.internal.Tags next;
}

-keep class com.drake.net.exception.** { *; }
