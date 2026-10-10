# 基础依赖升级说明

本 fork 将网络内核升级至 OkHttp 5.5.0，并同步更新 Kotlin、协程、AndroidX 与示例序列化库。
本仓库尚未发布新的 Maven/JitPack 制品；原作者的 `Net:3.7.0` 不包含这些修改。

## 版本与构建要求

| 组件 | 升级前 | 当前版本 |
| --- | --- | --- |
| OkHttp / MockWebServer | 4.10.0 | 5.5.0 / mockwebserver3 |
| Kotlin | 1.8.10 | 2.3.21 |
| kotlinx.coroutines | 1.6.1 | 1.11.0 |
| kotlinx.serialization JSON / Protobuf | 1.3.2 / 1.2.0 | 1.11.0 |
| Android Gradle Plugin / Gradle | 7.2.2 / 7.3.3 | 9.3.3 / 9.5.0 |
| Dokka | 1.4.32 | 2.2.0 |
| AppCompat / Lifecycle | 1.3.1 / 2.6.2 | 1.7.1 / 2.9.4 |
| Startup / DocumentFile | 1.0.0 / 1.0.1 | 1.2.0 / 1.1.0 |
| Gson / Moshi | 2.8.6 / 1.14.0 | 2.14.0 / 1.15.2 |
| Fastjson | 1.2.73 | fastjson2 2.0.65.android5 |
| Core / Navigation / RecyclerView | 1.9.0 / 2.3.5 / 1.2.0 | 1.17.0 / 2.9.7 / 1.4.0 |
| Material / ConstraintLayout | 1.3.0 / 2.0.4 | 1.13.0 / 2.2.2 |
| Glide / Chucker | 4.11.0 / 3.5.2 | 4.16.0 / 4.2.0 |

构建使用 JDK 17、Android SDK Platform 37.0 与 Build Tools 36.0.0。OkHttp 5.5 的 Android AAR 要求 `compileSdk 37`，其最低运行版本为 API 21。`net` 与 `sample` 的 `minSdk` 均为 21。

AGP 9 使用内置 Kotlin，示例 Data Binding 使用 `com.android.legacy-kapt`。Kotlin 插件依赖保留用于固定内置 Kotlin 与序列化编译器版本。Gradle 发行包配置了 SHA-256 校验。

AppCompat、Lifecycle、Core、Navigation、Material、Glide、Chucker 选用了支持 API 21 的稳定版本。更高的部分版本要求 API 23。BRV、Engine 等示例配套库保持现有 API，未在本次网络内核升级中重构其 UI 调用。

## 兼容处理

| 范围 | 行为 |
| --- | --- |
| 请求标签 | 适配 OkHttp 5.5 的不可变 Tags 链表，保留 `tags()` 可变视图与 `Request.tagOf(value)`。修改不会污染此前创建的 Builder 或 Request。 |
| 内部 API | 将无法由公开 API 实现的标签枚举、可变请求头、共享磁盘缓存操作集中于 `OkHttpUtils`；增加精确 consumer R8 规则。后续升级 OkHttp 仍需重跑这些测试。 |
| 缓存 | 保持现有 SHA-1 缓存键与元数据格式，与 OkHttp 标准 HTTP 缓存共用同一个 DiskLruCache；过期响应释放快照，中断写入废弃事务，Windows 在关闭快照后安全更新元数据；关闭缓存后放弃待执行的更新。 |
| 上传 | 透传 `isOneShot` / `isDuplex`，避免重试或日志窥读消耗一次性请求体；普通上传刷新而不关闭 OkHttp 管理的输出流；双工正文由发送方成功关闭输出流后通知完成。 |
| 下载 | EOF、读取异常和主动关闭均清理请求记录，多监听器都收到完成进度；下载异常关闭源与文件流，MD5 命中本地文件也释放本次响应。 |
| 协程 | 请求使用可取消的 OkHttp enqueue 桥接，取消 Deferred 或父 Job 时取消 Call；保留单个网络请求失败的隔离语义，成功完成不会遗留监督 Job。 |
| 转换错误 | 同步与异步转换失败都关闭响应，避免连接与请求记录泄漏；成功返回 Response 时仍由调用方关闭。 |
| Cookie | SQLite 升级为版本 2，迁移旧数据，按 domain/name/path 区分 Cookie；使用 OkHttp 公开匹配规则处理 path、secure、hostOnly 与过期删除；子域删除与读取的父域 Cookie 范围一致。 |
| 生命周期 | 新版 ViewModel 的 closeable 机制继续取消网络作用域；Interval 停止时取消独立 ticker 生产协程。 |
| Android 权限 | 库清单补全 `isNetworking()` 调用所需的普通权限 `ACCESS_NETWORK_STATE`，不需要运行时权限申请。 |
| 示例 | 迁移 MockWebServer 5 Builder / RecordedRequest API；fastjson2 使用 Android 5 制品与局部反射 provider + FieldBased，避免 Kotlin 附加构造器导致丢失属性，并保留模型的 R8 反射信息；核对 JSON 和 Protobuf 生成序列化器。 |

`tags()` 返回的是可变视图而非 OkHttp 的原始 Map，写入 null 等同于移除标签。请求执行期间不应并发修改标签。依赖依然由应用显式提供 OkHttp，必须使用经过此分支验证的 5.5.x 版本；4.x 不再是兼容目标。

## 测试与复现

```powershell
.\gradlew.bat :net:testDebugUnitTest :net:testReleaseUnitTest :net:testMinifiedTags :net:lintRelease :sample:testDebugUnitTest :sample:compileDebugJavaWithJavac :sample:minifyReleaseWithR8 :sample:lintVitalRelease --console=plain
.\gradlew.bat :net:dokkaGeneratePublicationHtml "-PdokkaOutputDir=$PWD\build\dokka-verification" --console=plain
```

测试使用真实 OkHttp、MockWebServer、磁盘缓存、SQLite 和 Robolectric 4.14.1，网络请求全部访问本地服务器，TLS 使用测试生成的证书。

| 测试范围 | 最终结果 | 覆盖场景 |
| --- | --- | --- |
| RequestCompatibilityTest | debug 16 / release 16 通过 | 空标签、类型键、增删改、Map 迭代、快照隔离、KType、请求头与宽松缓存头解析 |
| BodyCompatibilityTest | debug 19 / release 19 通过 | 输出流所有权、异常传播、一次性/双工透传、双工关闭与失败、多监听器、未知长度、重发与响应关闭、非消耗日志窥读 |
| NetworkCompatibilityTest | debug 21 / release 21 通过 | GET 查询、表单与 multipart、下载落盘与 MD5 复用、重试上限、一次性重试保护、协程取消、父 Job、失败隔离与响应清理 |
| ForceCacheTest / CacheModeIntegrationTest | debug 25 / release 25 通过 | 缓存键、旧 journal、持久化、过期、事务中断、元数据更新与关闭竞态、四种缓存模式、标准 HTTP 缓存共存、真实 TLS 证书与缓存 |
| CookieLifecycleCompatibilityTest | debug 26 / release 26 通过 | API 21 / 28 的 SQLite 迁移、Cookie 匹配与父域删除、真实 HTTP Cookie 往返、ViewModel 清理、Interval 生产协程释放 |
| ConverterCompatibilityTest | API 21 / 28 合计 18 通过 | Kotlin 默认值、未知字段、嵌套泛型、业务错误、Protobuf、fastjson2、Gson 和 Moshi |
| testMinifiedTags | 通过 | 真实 R8 9.3.28 优化、混淆后执行 5,000 次标签替换及枚举、移除、派生请求隔离与可变请求头检查 |

2026-10-10 本地验证：共 232 次 JUnit 测试执行（`net` debug 107 + release 107，`sample` 18），零失败、零跳过；示例 debug 编译与 release R8 编译、Dokka V2 HTML 生成均通过。库的发布单元测试通过 `androidComponents` 显式开启，覆盖 AGP 9 默认关闭的发布测试变体。

发布静态检查也通过：`:net:lintRelease` 为 0 错误、10 警告，`:sample:lintVitalRelease` 成功。Lint 剩余警告包括选用兼容 API 21 的依赖版本、现有明文 HTTP 配置及显式 `UnSafeTrustManager`；本次不改变这些既有 API 的行为。

实际解析的 OkHttp 为 `okhttp-android:5.5.0`，Chucker 间接引用的 4.12.0 被 BOM 统一为 5.5.0，没有混入第二套 OkHttp 类。fastjson2 使用官方 `.android5` 版本后，R8 不再报告通用 JAR 中仅 API 26 支持的 MethodHandle 调用。

报告位于 `net/build/reports/tests/` 与 `sample/build/reports/tests/`。CI 使用同一组命令进行回归，不打包 APK。

验证环境为 Windows、JDK 17 与 Robolectric 4.14.1。API 21 / 28 是 Android framework shadow 验证；未进行真机或模拟器安装测试，也未搭建 HTTP/2 双工端到端服务。双工包装契约覆盖返回后继续写入、关闭和异常；HTTP、TLS、磁盘缓存均使用真实本地实现。现有 Android UI 弃用提示、Jetifier / Gradle 弃用提示以及部分旧 KDoc 链接警告仍存在，不影响本轮构建。
