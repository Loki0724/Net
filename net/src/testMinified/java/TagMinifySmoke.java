import java.util.Map;
import okhttp3.OkHttpUtils;
import okhttp3.Request;

/**
 * R8 冒烟测试的独立入口，由 Gradle 使用真实 OkHttp、Okio 和 Kotlin 依赖编译、混淆后执行。
 * 不保留 Request 或桥接类的公共名称，确保真正验证项目 consumer-rules 的必要规则。
 */
public final class TagMinifySmoke {
    public static void main(String[] args) throws Exception {
        Request.Builder builder = new Request.Builder();
        Map<Class<?>, Object> builderTags = OkHttpUtils.tags(builder);
        check(builderTags.isEmpty(), "empty builder");
        builderTags.put(String.class, "original");
        Request request = builder.url("https://example.com/").build();
        Request copy = request.newBuilder().build();
        Map<Class<?>, Object> tags = OkHttpUtils.tags(request);
        check(tags.size() == 1, "enumerate original tags");

        // 多次替换并读取同一个 final 字段，验证优化器没有把原始标签读取常量化。
        for (int i = 0; i < 5000; i++) {
            String value = "updated-" + i;
            tags.put(String.class, value);
            check(value.equals(request.tag(String.class)), "native tag observes replacement");
            check("original".equals(copy.tag(String.class)), "snapshot remains independent");
        }
        tags.put(Integer.class, 42);
        check(tags.size() == 2, "enumerate mixed types");
        check(tags.keySet().contains(Integer.class), "boxed integer key");
        for (Map.Entry<Class<?>, Object> entry : tags.entrySet()) {
            if (entry.getKey().equals(Integer.class)) entry.setValue(40);
        }
        check(Integer.valueOf(40).equals(request.tag(Integer.class)), "entry writes through");
        tags.keySet().remove(String.class);
        check(request.tag(String.class) == null, "key set removes native tag");
        tags.clear();
        check(tags.isEmpty(), "clear");
        check(request.tag(Integer.class) == null, "native tag removed");

        // 构造器和已构建请求必须互不污染；两个可变视图则必须观察到同一所有者的修改。
        builderTags.put(String.class, "builder-updated");
        check("builder-updated".equals(builder.build().tag(String.class)), "builder writes through");
        check("original".equals(copy.tag(String.class)), "request remains independent of builder");
        OkHttpUtils.tags(request).put(String.class, "another-view");
        check("another-view".equals(tags.get(String.class)), "request map views share owner");

        OkHttpUtils.headers(builder).add("X-Smoke", "value");
        check("value".equals(builder.build().header("X-Smoke")), "headers write through");
        System.out.println("R8 mutable tag smoke checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
