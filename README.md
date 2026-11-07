# 红果TV

基于 Android 原生技术构建的红果短剧客户端项目。

- [当前功能说明与静态审计结果](功能说明.md)

## 版本信息与下载

以下版本号及下载链接由对应发布工作流自动维护。每次成功发布后，工作流会同步更新本栏，始终展示最新已发布版本。

### 本仓库测试版

<!-- TEST_RELEASE_INFO_START -->
- **当前版本：0.0.40**（versionCode：40）
- 应用包名：`com.x.hongguotv.bate`
- [下载测试版 APK：HongguoTV-0.0.40.apk](https://github.com/xycvip/HongGuoTV/releases/download/hongguotv-bate-v0.0.40/HongguoTV-0.0.40.apk)
- [查看测试版 0.0.40 发布说明](https://github.com/xycvip/HongGuoTV/releases/tag/hongguotv-bate-v0.0.40)
<!-- TEST_RELEASE_INFO_END -->

### 正式版

<!-- OFFICIAL_RELEASE_INFO_START -->
- **当前版本：1.1.37**（versionCode：2026110707）
- 应用包名：`com.x.hongguotv`
- [下载正式版 APK：HongguoTV-1.1.37.apk](https://github.com/xycvip/AppDownload/releases/download/hongguotv-v1.1.37/HongguoTV-1.1.37.apk)
- [查看正式版 1.1.37 发布说明](https://github.com/xycvip/AppDownload/releases/tag/hongguotv-v1.1.37)
<!-- OFFICIAL_RELEASE_INFO_END -->

> 测试版与正式版使用不同的应用包名，可并行安装；两者版本号独立维护。

## 项目来源

本应用派生于 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)，并在 [jycao2/hongguoTV](https://github.com/jycao2/hongguoTV) 基础上改动。

- **N3urda/hongguoTV**：提供原生 Kotlin / Android 工程基础，重点包括 Android 原生界面、内容访问及播放基础设施。
- **jycao2/hongguoTV**：在原生工程基础上继续增加和调整功能；本项目中的账号、弹幕、第三方 CMS 片源、本地备份、设备注册及播放会话等实现，与该仓库存在继承或改写关系。
- **本项目修改**：继续调整和扩展页面与控制器模块、网络请求管理、播放链路、状态管理、诊断及构建发布流程。具体以仓库当前代码为准。

### 源码比对说明

对当时三个仓库可访问的同路径文件进行 Git Blob SHA 比对：

- 本项目与 N3urda 仓库有 83 个同路径文件可比，其中 38 个文件内容完全一致。
- 本项目与 jycao2 仓库有 115 个同路径文件可比，其中 67 个文件内容完全一致。

完全一致表示对应文件的 Git Blob SHA 相同；其余同路径文件存在内容差异，可能经过修改、扩展或重构，不能仅凭文件名判断为原创。两个上游仓库之间也可能存在共同继承内容，因此来源判断应结合文件历史、源码注释和具体实现。以上数字是比对时点的结果，不代表全部代码的版权来源结论。

## 技术架构与实现方式

### Android 原生应用

- 使用 **Kotlin** 编写 Android 原生应用，界面主要采用 Android View、Activity、RecyclerView 和自定义 View。
- 工程按 `app` 与 `core` 分层：`app` 负责界面、交互、播放器及 Android 生命周期；`core` 承担内容请求、数据处理、签名、播放地址和媒体处理等逻辑。
- 使用 Gradle Kotlin DSL 管理工程与依赖。
- 当前应用标识为 `com.x.hongguotv`，Kotlin namespace 为 `com.hongguotv.nativeapp`；最低系统版本配置为 Android 8.0 / API 26。

原生 Kotlin 工程基础来自 N3urda/hongguoTV 的 `kotlin-tv`，并结合 jycao2/hongguoTV 后续工程内容持续修改。

### 视频播放

- 使用 **AndroidX Media3 / ExoPlayer** 作为播放引擎，包含 ExoPlayer、HLS 和 PlayerView 组件。
- 播放链路从内容接口解析播放资源，通过媒体源、数据源及播放会话组织请求。
- 项目包含播放预加载、进度记录、播放状态管理、清晰度选择、播放失败恢复和第三方片源播放器等实现。
- 加密 MP4 的索引、样本及子样本信息由媒体处理逻辑解析，并非简单地把加密地址直接交给播放器。
- 实际解码由 Android 设备上的 MediaCodec / 系统解码能力及 Media3 播放链路完成，设备支持的编码格式会影响播放结果。

Media3 原生播放基础来自 N3urda/hongguoTV 的 Kotlin 工程；后续播放会话、资源调度、加密媒体处理及第三方片源播放功能结合 jycao2/hongguoTV 的实现继续修改。Media3 本身来自 AndroidX Media 项目。

### 红果内容接口与请求签名

- 使用 **OkHttp** 发起 HTTP 请求，处理请求超时、响应读取、Cookie、请求取消及错误状态。
- 使用 Kotlin 实现内容接口参数组织、设备参数、请求头和签名流程。
- 核心算法涉及 MD5、SM3、字节序转换、Protobuf 风格字段编码及请求签名数据构造。
- 播放接口包含匿名设备注册与进程内播放会话管理；注册信息和播放请求身份在会话中复用。
- 播放地址解析包含多接口尝试、备用资源及异常恢复逻辑。

红果请求、接口访问和原生业务基础继承自 N3urda/hongguoTV，并在 jycao2/hongguoTV 基础上继续修改。

其中：
- `Signer.kt` 文件注明为 drpys 项目指定提交的 Kotlin 移植，来源为 [woshishiq1/drpys](https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715) 中的 `spider/js/红果果[短].js`。
- `MediaCrypto.kt` 文件注明为 drpys 相关实现的 Kotlin 适配。具体算法来源和许可证信息应同时参照上游 `kotlin-tv/NOTICE.md` 及 GPL-3.0 许可。
- 播放接口六头签名机制参考并移植自 [fyworld/hongguoTV](https://github.com/fyworld/hongguoTV)，对应上游提交为 [`add2dd8`](https://github.com/fyworld/hongguoTV/commit/add2dd8f248a17755f3a21c9c6a52bbddbefe5bd)。本项目将其签名实现适配到现有 `Signer.kt`，并在 `PlaybackRepository.kt` 的新版 GET 与旧版 POST 播放请求中接入；设备注册、播放会话及请求调度仍以本项目现有实现为基础，并非整套复制上游工程。上游仓库采用 GPL-3.0，本项目根目录亦提供 GPL-3.0 许可证。

### 加密媒体处理

- 通过字节与媒体容器解析逻辑读取 MP4 / ISO BMFF box 结构。
- 处理 `moov`、轨道、样本大小、块偏移及辅助加密信息等索引。
- 根据样本加密信息生成分段读取所需的数据，并保留原始视频编码标识。
- 对输入长度、索引数量、偏移范围和样本重叠等情况进行边界检查。

相关算法移植来源为 drpys；Kotlin 适配及 Android 播放链路整合沿袭上游原生工程，并在当前代码中继续调整。

### 第三方 CMS 片源

- 使用 CMS 接口约定解析分类、列表、详情、搜索建议和播放地址。
- 通过独立的 `ThirdCmsClient` 访问第三方片源，不复用红果内容请求的签名和 Cookie 会话。
- 对片源进行接口格式校验、探活、缓存、超时控制及请求取消。
- 浏览、搜索、详情、导入、播放等流程由独立状态对象、请求协调器和渲染组件配合完成。

第三方 CMS 和片源管理相关基础来自 jycao2/hongguoTV，并在当前项目中拆分、改写和扩展。

### 账号、收藏、历史与弹幕

- 账号功能包含二维码认证及账号数据协调逻辑。
- 收藏、观看历史和进度分别由本地存储及相关协调器管理；云端同步按对应接口实现。
- 弹幕使用独立的数据仓库、加载逻辑、设置状态及自定义绘制视图。
- UI 状态与数据操作分离，减少页面组件直接承担全部业务逻辑。

相关功能主要沿袭并改写自 jycao2/hongguoTV；底层原生工程结构继承自 N3urda/hongguoTV。

### 网络任务与缓存

- 使用 OkHttp 进行网络访问。
- 使用线程池和异步任务协调网络请求、封面加载、测速、预加载及数据处理。
- 对部分请求设置缓存有效期、响应体大小限制、超时和取消机制。
- 使用内存 Cookie 管理及请求作用域控制请求生命周期。

基础网络与内容访问结构来自 N3urda/hongguoTV；后续缓存、任务协调、取消和分模块实现结合 jycao2/hongguoTV 及本项目修改。

### 电视遥控器与界面交互

- 使用 Android 原生焦点系统处理遥控器方向键、确认、返回及长按操作。
- 使用 RecyclerView 展示海报列表与网格。
- 使用自定义 View 绘制弹幕、状态指示和部分播放交互。
- 页面状态、导航状态、播放状态及第三方片源状态通过独立状态类管理。

原生电视界面基础来自 N3urda/hongguoTV；当前界面布局、交互和模块拆分包含 jycao2/hongguoTV 的后续实现及本项目修改。

## 主要第三方依赖

| 依赖 | 用途 | 项目地址 |
|---|---|---|
| Kotlin | 应用及核心逻辑开发语言 | [JetBrains Kotlin](https://github.com/JetBrains/kotlin) |
| AndroidX Core / RecyclerView | Android 基础能力及列表 | [AndroidX](https://github.com/androidx/androidx) |
| AndroidX Media3 | 视频播放、HLS、播放器 UI | [AndroidX Media](https://github.com/androidx/media) |
| OkHttp | HTTP 网络请求 | [Square OkHttp](https://github.com/square/okhttp) |
| ZXing Core | 二维码生成与识别相关能力 | [ZXing](https://github.com/zxing/zxing) |
| Bouncy Castle | SM3 等密码学算法 | [Bouncy Castle](https://github.com/bcgit/bc-java) |
| jsoup | HTML 内容解析 | [jsoup](https://github.com/jhy/jsoup) |
| org.json | JSON 数据处理及 JVM 测试 | [JSON-java](https://github.com/stleary/JSON-java) |
| JUnit | 单元测试 | [JUnit 4](https://github.com/junit-team/junit4) |
| desugar_jdk_libs | Java API 兼容支持 | [Android desugar](https://github.com/google/desugar_jdk_libs) |

依赖版本和实际打包范围以 `kotlin-tv/app/build.gradle.kts`、`kotlin-tv/core/build.gradle.kts` 为准。

## 测试与构建发布

- 使用 JUnit 编写 JVM 单元测试，覆盖内容解析、签名、加密媒体、播放规则、第三方片源及状态协调等逻辑。
- 使用 Android Gradle Plugin、Kotlin Gradle Plugin 和 Gradle Wrapper 构建。
- 仓库包含发布构建配置与发布脚本；当前配置文件位置和脚本引用存在待核查项，详见 [功能说明](功能说明.md) 的静态审计记录，暂不据此认定自动发布链路已可用。
- Release 构建从环境变量或本地属性读取签名配置；签名密钥不应提交到源码仓库。

Gradle / Kotlin 原生构建基础来自上游原生工程；当前工作流和发布逻辑以本仓库实际配置为准。

## 许可证与使用说明

- 本项目仓库采用 **GNU GPL v3.0**，许可证全文见仓库根目录 [LICENSE](LICENSE)。
- 派生代码及修改部分应遵守 GPL-3.0 的适用要求。
- 上游项目、drpys 及第三方依赖的原始著作权和许可证仍归各自权利人所有。
- 本项目不是红果内容平台官方客户端；平台接口、账号服务和内容资源由相应服务提供方控制。

**来源声明：本项目派生于 N3urda/hongguoTV，并在 jycao2/hongguoTV 基础上改动。** 本 README 按可核验的代码结构、文件内容和源码注释整理；对无法仅凭当前代码确定的细节，不作原创性或权属推断。
