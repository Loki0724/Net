# 只保留 JVM 测试入口；请求类、桥接类及依赖使用 consumer-rules.pro 中的真实规则。
-keep class TagMinifySmoke {
    public static void main(java.lang.String[]);
}

# 本测试运行在 JVM 上，OkHttp 支持的可选平台和注解没有加入测试程序输入。
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**
-dontwarn dalvik.**
-dontwarn android.**
-dontwarn kotlin.reflect.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
