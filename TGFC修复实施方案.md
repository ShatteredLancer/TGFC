# TGFC 客户端修复实施方案

## 1. 目标与当前结论

目标是恢复 TGFC 安卓客户端对 `https://bbs.tgfcer.com` 的访问和登录能力，并最终产出可安装、可验证的 APK。

记录日期：2026-09-10，最近更新：2026-09-11。评估基线：`d176b493`。当前分支为 `TGFC-own`，`origin` 为 `https://github.com/ShatteredLancer/TGFC.git`。环境搭建、登录代码改造、debug/release 编译和静态检查已经完成；开发机仍没有 ADB 设备，真机安装和账号验证由用户手工执行。用户已确认第五版能够连接并完成主站登录，当前继续验证 BBS 登录收尾和 `s.tgfcer.com` 水区会话共享。

实施前评估确认：

- 论坛普通页面仍可访问，帖子列表和现有 HTML 解析逻辑原则上可以保留。
- 当前网页登录页包含 Google reCAPTCHA，脚本来自 `www.recaptcha.net`，并包含动态 `formhash` 隐藏字段；未验证服务端是否强制校验该字段。
- 基线客户端已有旧的 reCAPTCHA 支持，但验证码在 WAP WebView 中获取，登录由独立的 OkHttp Cookie 会话提交，两者没有 Cookie 同步。BBS 与 WAP 使用相同 site key，不能仅凭跨子域名断定 token 无效。
- 基线 `LoginHelper.login()` 把 `formhash` 固定为空字符串，获取代码被注释，与网页流程存在差异；完整 WebView 登录可让网页自行提交该字段。
- `fid=25` 的请求会跳转到 `s.tgfcer.com`，基线代码已有相关 URL 切换逻辑，需要回归验证。
- 初次执行 `assembleDebug` 时，环境没有可用的 `JAVA_HOME` 和项目 `local.properties`，因此先完成了第 3 节所述的便携工具链搭建。

网络证据：直连返回 403，经 `127.0.0.1:1080` 代理访问登录页返回 200；使用 App 当前 Chrome 87 UA 也能取得正常页面。数码区访客页面包含帖子列表，水区访客页面无帖子行。上述检查不能证明手机网络相同，也未使用用户账号执行登录。因此，登录流程是重点修复方向，具体失败原因尚未通过真机复现闭环确认。

### 1.1 当前实施结果

- 已建立仓库内便携工具链：Temurin JDK 8u504、Temurin JDK 17.0.20.1、Android SDK Platform 28、Build Tools 26.0.2、Platform Tools 37.0.1。
- 原始代码的基线 debug APK 构建成功，SHA-256 为 `E84F2661E1FDC22162F715489B72833705E6023117E2EEC96663A6E387A50CBE`。
- 第六版曾把登录入口切换为论坛 WAP 登录页；两次真机结果均证明 WAP 可以登录自身页面，但不能产生 App 所需的 BBS 登录态。该方案已判定不可用，下一版应恢复 BBS 官方认证链，只改造其移动端显示。
- 已停止后台自动提交旧密码和一次性验证码；会话失效时改为通知主界面打开网页登录。
- 已修复 Cookie 的 TGFC 域判断、host/domain/path/secure 匹配、过期过滤和持久化键冲突；WebView 导入的请求 Cookie 显式设为 Version 0，避免 Java 默认 Version 1 生成 `$Version/$Path/$Domain` 字段而不被旧论坛正确解析。`tgc_*` 按 `.tgfcer.com` 跨子域共享，并按名称和路径移除旧的 host-only 重复值；退出登录同步清理 WebView 与 OkHttp Cookie。
- 登录确认现在区分“未导入 `tgc_auth`”和“已导入但 BBS 仍返回未登录”，最多重试三次且失败时不再清空刚导入的会话；诊断日志只记录布尔状态与身份选择器名称，不记录 Cookie 值、密码或验证码 token。
- 登录确认以 BBS 服务端页面返回的匿名登录标记为准，不再要求固定的 `logout` 页头链接；列表 Loader 和全局登录事件会识别正在显示的登录 WebView，避免旧回调在认证完成后再次清空 Cookie 或弹出登录框；过期 Cookie 不再阻止重新打开登录页。
- 已去除 `fid=25` 域切换的跨请求状态污染；水区列表和由列表打开的帖子详情显式走 `s.tgfcer.com`。水区拒绝会话时只报告板块级错误，不再打开无效的二次登录页或清理主站会话；App 内更新源已切换至 `ShatteredLancer/TGFC`。
- 最终 debug 与未签名 release APK 均已构建成功，详情见第 5、7 节。

## 2. 实施原则

1. 先建立可重复的构建环境，并记录基线构建结果。
2. 登录改造优先保证验证码、表单、Cookie 在同一个 WebView 会话内完成。
3. 登录成功后，将 WebView Cookie 同步到现有 OkHttp CookieStore，尽量不重写帖子、回复、收藏等业务代码。
4. 每个阶段都有可执行的验收标准；未通过时先处理当前阶段，不叠加后续变量。
5. 不尝试绕过或伪造 reCAPTCHA，只使用论坛公开登录页面完成验证。

## 3. 阶段一：环境准备与构建基线

### 3.1 需要准备的软件

- JDK：优先使用与旧 Android Gradle Plugin 2.3.0 兼容的 JDK 8。
- Android SDK：先准备 `platforms;android-28`、`build-tools;26.0.2` 和 `platform-tools`，其他组件按构建需求补齐。Support Library 27.1.1 不意味着必须额外安装 API 27 平台。
- Android SDK Platform-Tools：用于 `adb` 安装和日志采集。
- Git、代理：访问 Gradle 依赖和论坛时可使用 `127.0.0.1:1080`。

本次实际安装位置与版本：

- JDK 8：`.local-tools/jdk8/jdk8u504-b01`，Temurin `1.8.0_504-b01`，用于 Gradle 构建。
- JDK 17：`.local-tools/jdk17/jdk-17.0.20.1+1`，仅用于新版 SDK 管理工具。
- Android SDK：`.local-tools/android-sdk`。
- Android Platform：API 28，包修订版 6。
- Build Tools：26.0.2；Platform Tools：37.0.1。
- Gradle Wrapper：3.5；Android Gradle Plugin：2.3.0。

`.local-tools/`、`local.properties` 和 `gradle.properties` 均被 Git 忽略，不会把便携工具链、本机 SDK 路径或代理写入提交。

### 3.2 环境变量与项目配置

配置并确认：

```powershell
$env:JAVA_HOME = "<JDK 8 安装目录>"
$env:Path = "$env:JAVA_HOME\bin;<Android SDK>\platform-tools;<Android SDK>\tools;" + $env:Path
```

在仓库根目录创建 `local.properties`，内容指向 Android SDK：

```properties
sdk.dir=C:\\Android\\Sdk
```

代理只在确有需要时配置到 Gradle/命令行，避免把个人代理地址写入版本库。

可在已被 `.gitignore` 排除的项目 `gradle.properties` 中设置 `systemProp.http.proxyHost=127.0.0.1`、`systemProp.http.proxyPort=1080`，HTTPS 使用对应的 `systemProp.https.*` 属性。检查 Gradle wrapper 下载也能经过代理；Git/curl 代理不会自动作用于 Gradle。现代 SDK command-line tools 可能要求比 JDK 8 更新的 Java，可为 SDK 管理工具单独使用新版 JDK，旧项目构建仍固定为 JDK 8。不要为了下载依赖关闭 TLS 校验。

### 3.3 基线命令

```powershell
java -version
adb version
.\gradlew.bat --version
.\gradlew.bat assembleDebug --no-daemon
```

基线通过标准：能够生成 `tgfc/build/outputs/apk/` 下的 debug APK；若失败，记录完整错误、缺失 SDK 组件和依赖下载问题，再修改构建配置。

本次基线已经通过。构建依赖源中的阿里云仓库由 HTTP 改为 HTTPS；未升级 Gradle、AGP 或迁移 AndroidX。旧 AGP 读取新版 SDK 元数据时会输出 schema 与缺失 NDK 目录警告，但不影响 Java 编译和 APK 生成。

### 3.4 老项目兼容处理

优先尝试现有 Gradle 3.5 和 Android Gradle Plugin 2.3.0，先解决 JDK、SDK、仓库和依赖下载问题。将旧 HTTP 仓库迁移至可信 HTTPS 源，逐项确认旧依赖仍可取得；镜像不能保证包含所有 JCenter 历史包。若插件兼容性或依赖可用性阻碍构建，再选择最小必要的 Gradle/AGP 或依赖升级，并核对：

- `compile` 到 `implementation` 的依赖语法；
- `buildToolsVersion` 和 SDK 版本；
- 旧 Support Library、JCenter 依赖；
- release 输出文件名配置；
- 是否确实需要 AndroidX 迁移；不把全量迁移当作此次登录修复的默认前提。

每次构建链升级都单独提交或保留清晰 diff，避免与登录逻辑改造混在一起。

## 4. 阶段二：登录代码改造

### 4.1 推荐实现：BBS 官方认证链与移动化显示

登录入口采用论坛官方 BBS 登录页，保持表单、reCAPTCHA 和 Cookie 在同一主机及同一次提交中完成：

`https://bbs.tgfcer.com/logging.php?action=login`

流程：

1. WebView 加载论坛官方 BBS 登录页，并启用 JavaScript、DOM Storage、第一方和第三方 Cookie。
2. 用户在 WebView 中填写账号密码并完成 reCAPTCHA。
3. WebView 直接提交论坛页面生成的表单和 `g-recaptcha-response`，App 不读取或伪造验证码 token。
4. 观察页面跳转和 Cookie 变化作为登录成功候选信号；URL、标题或欢迎文本不能单独作为成功依据。
5. 从 Android WebView CookieManager 分别读取 `bbs.tgfcer.com`、`wap.tgfcer.com` 和 `s.tgfcer.com` 可见的 `tgc_auth`、`tgc_sid`、`PHPSESSID` 等 Cookie。
6. 把 Cookie 写入应用自己的 CookieStore，供 OkHttp 后续请求使用。
7. 通过 OkHttp 请求 `https://bbs.tgfcer.com/index.php` 确认实际身份并解析用户名和 UID，再请求 `https://s.tgfcer.com/forumdisplay.php?fid=25` 探测水区是否接受同一会话。BBS 验证成功后发送登录事件并刷新首页、账户栏和收藏缓存；水区探测失败不否定主站登录，也不清理 Cookie。

BBS 与 WAP 使用同一个 reCAPTCHA site key，但不是同一登录协议。WAP 表单提交到自己的 `index.php?action=login`，使用 host-only `PHPSESSID`；BBS 表单提交到 `logging.php?action=login`，使用动态 `formhash`、`loginfield`、`cookietime`、`loginsubmit` 以及 `.tgfcer.com` 的 `tgc_sid/tgc_auth`。reCAPTCHA response token 是两分钟内、只能验证一次的表单凭证，验证结果还包含解题页面 hostname；它不是论坛登录 Cookie。WAP 提交成功后 token 已被消费，WAP PHP 会话不能转换成 BBS 会话。移动体验应通过裁剪 BBS 页面无关区域、重排登录表单和适配 reCAPTCHA 容器实现，不能把实际提交端点换成 WAP。

### 4.2 必须修复的代码点

- `LoginHelper.java`
  - 不再使用空的 `formhash`。
  - 若保留 OkHttp 登录分支，必须先 GET 登录页并从 HTML 读取动态 `input[name=formhash]`。
  - 改进成功判断，优先检查认证 Cookie 和登录后页面，而不是只依赖固定中文文本。
  - 不在日志中输出密码或完整验证码 token。

- `LoginDialog.java`
  - 使用官方 WAP 完整登录页；若登录成功跳转到 BBS 桌面页，仍保留响应式样式作为过渡显示。
  - 删除依赖 JavaScript `alert()` 读取验证码 token 的逻辑。
  - 增加页面加载失败、验证失败、登录成功和取消状态处理。
  - 登录完成后再关闭 WebView，避免提前销毁 Cookie 会话。

- `OkHttpHelper.java` / `PersistentCookieStore.java`
  - 增加从 WebView Cookie 字符串导入 Cookie 的方法。
  - `CookieManager.getCookie(url)` 只返回请求 Cookie 的名称和值，不包含完整 domain、path、secure、HttpOnly 和到期时间，不能把它当成 `Set-Cookie` 完整恢复属性。
  - 优先评估以 WebView CookieManager 为统一来源、按目标 URL 提供 Cookie，并把 OkHttp 收到的 `Set-Cookie` 同步回去；若采用导入方案，必须明确属性缺失的处理方式，并用后续请求确认身份。
  - WAP、BBS 和 `s` 主机的 `PHPSESSID` 保持 host-only；论坛认证所需的 `tgc_*` Cookie 按 `.tgfcer.com` 域导入并跨子域去重。实际登录优先使用 BBS 产生的会话，避免把 WAP 专用会话误当成 BBS 登录态。
  - `isLoggedIn()` 不应只依赖历史 Cookie；必要时使用一次轻量页面请求确认登录状态。

- `HiSettingsHelper.java`
  - 继续保存账号等必要设置，但不持久化 reCAPTCHA token。
  - 移除对长期保存密码和重用验证码的依赖；失败时不无条件抹掉用户填写的账号。

- `ThreadListLoader.java`、`DetailListLoader.java` 及其他重新登录入口
  - 会话过期时请求用户重新打开登录页，停止后台重复提交旧密码和一次性验证码。
  - 区分访客页面、登录页、验证页、网络错误和有效帖子页面。当前通过登录链接判断状态的逻辑会阻断部分可公开浏览内容，需确定并验证访客行为。
  - 退出登录同时清理 WebView 与原生网络会话，防止旧会话重新同步回来。

### 4.3 兼容性与安全要求

- 项目声明最低 API 14，但这不保证该系统的旧 WebView 能运行当前 reCAPTCHA。先记录目标手机的 Android 和 WebView 版本并验证；是否提高最低版本根据实测决定。
- WebView 使用 `CookieManager.getInstance()`，并在对应版本调用 Cookie 同步接口。
- 不把账号密码、Cookie、验证码 token 写入普通日志或错误上报。
- 论坛域名和 `s.tgfcer.com` 的 Cookie 必须分别验证，不能假设两个域名完全等价。
- 只在可信论坛来源执行登录状态检测；不忽略证书错误。验证码 iframe 所需 Cookie 策略按系统版本和真机结果配置。

## 5. 阶段三：编译、安装与自动检查

### 5.1 编译命令

```powershell
.\gradlew.bat clean assembleDebug --no-daemon
```

最终验证实际执行：

```powershell
.\gradlew.bat clean assembleDebug assembleRelease --no-daemon
```

结果为 `BUILD SUCCESSFUL`。之后修改 App 内更新仓库地址，又执行一次 `assembleDebug assembleRelease`，结果同样成功。

输出 APK 后检查：

```powershell
Get-ChildItem tgfc\build\outputs\apk -Recurse
```

### 5.2 APK 安装

```powershell
adb devices
adb install -r <debug-apk路径>
```

如果设备上已有正式版，`-r` 覆盖通常要求相同包名、匹配的签名且版本号满足升级条件。调试签名通常不匹配原发布版，优先为 debug 配置 applicationIdSuffix（例如 `.test`）和可区分名称来并行安装，并验证包名变化后的资源、权限及跳转行为。不要自动卸载或清除旧版数据；卸载会丢失本地设置和 Cookie。

### 5.3 基础运行检查

- App 能启动且无立即崩溃。
- 主页面可以加载公开版块的帖子列表；仅侧边栏静态版块名称出现不算网络请求成功。
- `fid=10` 等普通版块列表可以显示。
- 登录后 `fid=25` 跳转到 `s.tgfcer.com` 能显示有权限访问的内容；访客状态应显示明确的登录或权限提示。
- 网络不可用时仍显示明确错误，不发生无限等待。

当前开发机没有 ADB 设备连接。用户已手工安装第一版 debug，确认 App 可以启动并打开登录流程；debug 使用独立包名 `net.jejer.tgfc.ng.test`，可与正式版并存。登录 UI 的首次测试结果和修复见阶段四。

静态检查已执行 `gradlew lint`。命令对 debug/release 各报告 401 项，合并 XML 中为 388 条唯一记录，任务按旧项目配置失败。5 个错误级问题为 3 个既有 `AppCompatCustomView`、1 个 Support Library 27 与 compileSdk 28 的 `GradleCompatible`，以及 1 个旧 SDK 元数据导致的 `LintError`。本次 WebView 登录新增 1 个 `SetJavaScriptEnabled` 警告，JavaScript 是论坛登录页和 reCAPTCHA 的必要条件。完整报告位于 `tgfc/build/reports/lint-results.html`。

## 6. 阶段四：登录真机验证

测试前通过代理确保设备或测试网络可以访问论坛和 reCAPTCHA 服务。

手机中的 `127.0.0.1` 指手机自身，不能直接使用电脑的回环代理地址。按实际设备选择已可用的网络、模拟器宿主地址或受控的转发方式；不默认向局域网开放电脑代理。登录与后续请求保持一致网络出口进行首次验证。

### 6.1 第一次真机结果与修复

2026-09-10 第一版 debug 已由用户手工安装。登录页和 reCAPTCHA 图片挑战能够加载，但桌面版 BBS 页面以接近桌面宽度整体缩放，挑战窗口被压缩、裁切到左侧，图片与操作按钮无法正常使用。该结果说明网络、JavaScript 和第三方验证码资源已基本加载，失败点是布局而非验证码缺失。

针对该结果先完成第二版修复：入口切换到官方 WAP 完整登录页；显式关闭 WebView 宽屏概览缩放；补充移动 viewport、全宽输入框和触控尺寸；登录检测同时读取 WAP/BBS Cookie；原生 Cookie 导入覆盖 WAP/BBS/`s` 三个主机。第二次测试中 WAP 页面显示“lancer 成功登录”，但原生 BBS 首页请求仍是未登录。第三版补全触发校验后结果相同，第四版因而改回 BBS 官方登录页。第五版修复 Version 1 Cookie 格式后，BBS 官方页面登录成功并能接管 App。

第四版真机截图 `微信图片_20260910205710_46_66.jpg` 和 `微信图片_20260910205711_47_66.jpg` 显示 BBS 页头已经出现“您好：lancer”和“退出”，且可以浏览登录后的首页，证明 BBS WebView 认证已成功；但登录窗口没有关闭，后台 App 仍处于等待登录状态，失败范围已收敛到 WebView Cookie 导入和原生二次确认。检查发现 `new HttpCookie(name, value)` 默认创建 Version 1 Cookie，实际请求头包含 `$Version="1"`、`$Path` 和 `$Domain`；改为 Version 0 后请求头才是旧 Discuz/PHP 期望的普通 `tgc_auth=...` 形式。

第五版已把 WebView 导入 Cookie 固定为 Version 0，并让 BBS 域的最新 Cookie 覆盖其他子域同名值；用户真机确认该版本已经能够连接并登录。随后发现登录仍使用桌面版页面，并且进入 `fid=25` 会弹出二次验证；旧 `LoginDialog.onCreate()` 会清空已有 Cookie，因而返回普通板块后也再次要求登录。

2026-09-11 第六版改为 WAP 完整登录页，并在导入时将 `tgc_*` 统一为 `.tgfcer.com` 域、删除同名同路径的旧 host-only Cookie。登录确认先验证 BBS，再探测水区；水区失败不影响主站成功。`s.tgfcer.com/logging.php?action=login` 实测会 302 到 `club.tgfcer.com` 并形成重定向循环，因此已完全移除该二次登录路径。水区列表和帖子详情显式使用 `s` 子域；如果水区仍拒绝共享会话，只显示板块级错误，绝不清理 Cookie 或弹出第二个登录框。

第六版两次真机截图均显示 WAP 成功页和已登录的 WAP 首页，但 App 仍未登录。结合实时表单/响应头和仓库历史实现确认：WAP 只完成自己的 PHP 会话；它不提供 BBS 可用的认证结果。旧 App 也只是从尚未提交的 WAP 页面读取验证码 response，再单独 POST BBS，并非登录 WAP 后同步 Cookie。第六版的 WAP 完整登录方案至此停止，不再通过增加页面成功识别或重试次数继续试错。

2026-09-11 第七版恢复 `https://bbs.tgfcer.com/logging.php?action=login` 作为唯一认证入口，并将 WebView Cookie 导入时的优先主机恢复为 BBS。移动适配只注入 viewport 和 CSS，不替换、读取或重新提交论坛表单：隐藏与认证无关的桌面页头、导航和页脚，将表格表单改为单列全宽布局，输入框/下拉框/提交按钮使用至少 46px 的触控高度，并为 304px 宽的 reCAPTCHA 保留独立宽度和窄屏缩放。论坛生成的动态 `formhash`、reCAPTCHA iframe、登录端点及 Cookie 签发流程保持原样。

第七版首轮真机结果显示 BBS 验证后的 Toast 为“主站已登录”，但主列表随即再次显示未登录提示。检查发现登录确认成功后紧接着访问了 `s.tgfcer.com`，该水区响应会更新跨子域 `tgc_sid`，这一步可能覆盖刚建立的主站会话。为恢复第五版已验证的登录时序，本轮移除登录收尾阶段的水区主动探测，登录成功只以 BBS 响应为准；水区仍在用户实际进入时按板块级错误处理，失败不清理 BBS Cookie。

第八版进一步收紧 WebView Cookie 接管：`CookieManager.getCookie()` 不返回 Cookie 的 domain/path 属性，不能安全区分 BBS、WAP 和水区的同名值。登录确认现在只从 BBS URL 导入 Cookie，再由 `.tgfcer.com` 域规则供后续水区请求使用，避免旧 WAP 或水区 Cookie 覆盖刚完成的 BBS 登录。

第九版修复登录完成后的竞态：旧列表 Loader 在网页登录期间的未登录回调不再重复触发登录流程；主界面仅以“登录 WebView 是否正在显示”抑制重复弹框，不把持久化 Cookie 的存在误判为有效会话；BBS 登录页页头结构变化也不会再因缺少 `logout` 链接而误判失败。最终确认要求 BBS `tgc_auth` 已导入且 BBS 响应不再包含匿名登录标记。

第九版真机结果确认普通板块已经可以登录，但进入 `s.tgfcer.com` 水区后页面为空，并提示“水区子域未接受当前登录会话”；随后切回普通板块时，BBS 会话也失效。匿名对照请求进一步确认：先由 BBS 签发 `tgc_sid`，再把该 SID 原样发给水区，水区会立即返回另一个 `tgc_sid`；把水区 SID 发回 BBS，BBS 又会再次替换。两个后端都把互不兼容的 SID 错误声明为 `.tgfcer.com`，因此遵循标准浏览器 Cookie 域规则会在两个子域之间反复覆盖会话。

第十版按 Cookie 的职责拆分作用域：BBS 签发的 `tgc_auth` 继续作为跨子域认证凭证；`tgc_sid` 在接收时强制转成当前主机专属 Cookie，使 BBS 与水区各自保有 SID。水区响应不得覆盖或删除 BBS 的权威 `tgc_auth`。升级时无法判定归属的旧全域 SID 会被丢弃，由两个主机在下一次请求时分别重建；新 SID 继续持久化，因此切换板块和重启 App 后不应再互相破坏。该方案不伪造认证结果：如果水区后端最终仍拒绝 BBS 的 `tgc_auth`，App 会保留普通区登录并报告板块级错误。

第十版真机结果确认 SID 隔离有效：进入水区再返回普通区时，普通区登录状态不再被破坏；但水区仍返回未登录页面。为停止继续猜测 Cookie 关系，用户在全新浏览器会话中完成 BBS 登录后，抓取了包含敏感 Cookie 元数据的 HAR，并依次访问普通区列表/帖子、水区列表/帖子及返回普通区。HAR 共 71 个请求、13 个页面阶段，普通区和水区帖子响应均包含退出入口且没有登录表单，证明浏览器中的两个子域同时处于登录态。

HAR 的值等价比较确认：`tgc_auth` 和 `tgc_cookietime` 在 BBS 与水区完全相同；`PHPSESSID` 是两个主机各自的 host-only 会话；`tgc_sid` 在进入水区和返回 BBS 时分别轮换。进一步通过 `127.0.0.1:1080` 做只读对照请求：只发送 BBS 的共享 Cookie、刻意排除水区 `PHPSESSID` 和 `tgc_sid`，首次请求 `https://s.tgfcer.com/forumdisplay.php?fid=25` 就返回登录态并签发水区 SID，证明水区不需要第二次登录或第二次 reCAPTCHA。使用同一组 Cookie 但替换完整 User-Agent 后，BBS 和水区同时退回访客页；恢复抓包时的 User-Agent 后，两边同时恢复登录。这与旧 Discuz 使用 User-Agent 派生认证密钥的行为一致，根因是登录 WebView 与原生请求使用了不同 UA，而不是缺少水区 token。

第十一版在登录 WebView 加载表单前读取其实际 User-Agent，并由 `HiUtils` 持久保存；OkHttp、上传和图片请求继续统一通过 `HiUtils.getUserAgent()` 读取同一个值，因此当前进程及 App 重启后都与生成 `tgc_auth` 的 WebView 保持一致。第十版的 BBS/水区 SID 隔离规则保持不变。旧 Cookie 可能由另一 UA 生成，覆盖安装第十一版后必须重新完成一次 BBS 网页登录，不能用旧会话是否偶然可用作为验收结果。

### 6.2 登录成功场景

1. 使用独立测试包的新会话，必要时只清理测试包数据或 Cookie。
2. 打开登录页面。
3. 输入正确账号密码。
4. 完成人机验证。
5. 确认页面提示登录成功。
6. 返回首页，确认账户栏显示用户名。
7. 进入需要登录的页面，确认请求不再跳回登录页。
8. 重启 App，确认 Cookie 持久化后仍保持登录。

### 6.3 失败和边界场景

- 密码错误。
- 安全问题答案错误或未设置。
- 不完成 reCAPTCHA 直接提交。
- reCAPTCHA token 过期后提交。
- 网络/代理中断。
- WebView 登录成功但 Cookie 同步失败。
- `bbs.tgfcer.com` 与 `s.tgfcer.com` 页面交替访问。
- 退出登录后确认旧 Cookie 被清理。

### 6.3 日志和证据

使用以下命令采集崩溃和登录流程日志：

```powershell
adb logcat -c
adb logcat | Select-String "TGFC|AndroidRuntime|WebView|OkHttp"
```

日志中只保留状态码、URL 主机、页面状态和错误类型，不保存密码、Cookie 值或验证码 token。

手工验证由账号持有人完成验证码和密码输入。优先用收藏列表等只读登录功能验收；发帖、回复、上传等写入功能使用用户指定的测试位置。对 Cookie 域/路径/过期、会话状态转换、GBK 页面解析和取消登录后的生命周期增加有针对性的测试，再运行受影响模块的检查与 lint。

## 7. 阶段五：Release 构建与签名

Debug 验证通过后再处理 Release：

```powershell
.\gradlew.bat assembleRelease --no-daemon
```

需要提前确认：

- 是否有原应用的 keystore、alias 和密码；
- 是否要求覆盖现有安装；
- 是否只生成个人测试 APK。

没有原签名时，不能把新 APK 当作同一应用直接升级旧版。可选择卸载旧版后安装，或修改 applicationId 作为独立测试版本。

没有原应用签名时，使用专用 keystore 配置本地签名，或先用 `zipalign` 对齐再用 `apksigner` 签名；通过 `apksigner verify --verbose --print-certs <apk路径>` 检查结果。密钥与密码保存在仓库之外，不写入文档或提交记录。Release 开启混淆，必须安装签名后的实际 Release APK 再验证登录和 WebView 流程，不能以 debug 结果代替。当前仓库支持通过 `TGFC_RELEASE_SIGNING_FILE` 指向外部 properties 文件；未提供签名配置时仍可构建未签名包，但发布前必须提供签名配置。

已生成新的 ShatteredLancer release 密钥，位置为仓库外的 `C:\Users\Administrator\.tgfc\tgfc-release.jks`，对应配置为同目录的 `release-signing.properties`；两者均限制为当前账户和 SYSTEM 可读。原仓库全部远程分支、标签和历史对象均未发现旧 release keystore，因此新签名 APK 不能覆盖原作者签名的安装包，首次安装需卸载旧正式版或使用不同包名。密钥必须单独加密备份，丢失后无法发布后续可覆盖升级版本。证书 SHA-256 指纹为 `d290f1cd5bbec61518cb48611b9bb232d3a58eaa922baf51d6120acb667f3b77`，用于发布前核对。

2026-09-11 已完成 `clean assembleDebug assembleRelease --no-daemon`。正式 APK 为 `tgfc-ng-release-1.4.4.apk`，包名 `net.jejer.tgfc.ng`，版本 `1.4.4 (28)`；`apksigner.jar verify --verbose --print-certs` 确认 v1/v2 均有效，`zipalign -c -v 4` 通过。Build Tools 26.0.2 自带的 `apksigner.bat` 在当前精简 SDK 中因找不到 `find_java.bat` 会静默返回 0，不得用该脚本的退出码判定签名状态。

## 8. 交付物与阶段验收

每个阶段保留以下结果：

- 环境版本记录：JDK、Gradle、AGP、SDK、构建工具；
- 基线/修复后的构建日志；
- 代码变更和变更原因；
- debug APK 路径及 SHA-256；
- 真机测试记录，包括成功、失败和已知限制；
- release 签名方式及是否可覆盖旧版的结论。

APK 哈希可用 `Get-FileHash -Algorithm SHA256 <apk路径>` 记录；同时保存源码提交号或工作区差异、版本号、测试设备与 WebView 版本。

2026-09-11 第十一版构建产物：

| 构建 | 包名与版本 | 文件 | 字节数 | SHA-256 | 签名状态 |
| --- | --- | --- | ---: | --- | --- |
| Debug（第十一版，统一并持久化 WebView/原生 UA） | `net.jejer.tgfc.ng.test` / `1.4.4-test` (28) | `tgfc/build/outputs/apk/tgfc-debug.apk` | 4,156,980 | `2B13ABB84DEAADE2F0A617F0EB7B11271035510534DE718884A1F2EA32AEECFF` | Android debug 证书；v1/v2 签名校验通过，用于本轮真机验收 |
| Release（第十一版，统一并持久化 WebView/原生 UA） | `net.jejer.tgfc.ng` / `1.4.4` (28) | `tgfc/build/outputs/apk/tgfc-ng-release-1.4.4.apk` | 3,130,298 | `C4AEE92926087339AB8CDA9BA611D4E42FFA3A9FC3403C3F6E0AD2782F78BB37` | ShatteredLancer 新 RSA-4096 证书；v1/v2 签名及 zipalign 校验通过 |

最终验收标准：

- 能稳定构建 APK；
- WebView 中可完成论坛 reCAPTCHA 登录；
- Cookie 能同步到 OkHttp，登录后的列表、详情和至少一个需要登录的操作可用；
- 退出登录和重新启动后的状态正确；
- 不在日志或 APK 配置中泄露账号、密码、Cookie 或验证码 token。

## 9. 风险与停止条件

- 如果论坛进一步要求设备指纹、IP 绑定或新的挑战流程，纯客户端改造可能无法稳定解决，需要评估官方接口或人工登录后导入会话的方案。
- 如果旧 Gradle/AGP 无法在可获得的 JDK 上运行，先完成构建链升级，再开始业务代码修改。
- 如果没有原签名密钥，不承诺新 APK 能覆盖手机上的旧版本。
- 不以绕过 reCAPTCHA、抓取第三方 token 或伪造验证结果作为修复方案。

## 10. 分步执行与回滚

- [x] 仓库、截图和匿名网络响应评估。
- [x] 记录方案、已知限制和验收要求。
- [x] 阶段一：定位或安装工具链，修复依赖源，获得基线 debug APK。
- [x] 阶段二：完成 WebView 登录、统一会话与过期处理（代码和编译验证完成）。
- [x] 阶段三：测试包和针对性静态检查已完成；用户已手工安装并启动第一版 debug。
- [ ] 阶段四：第五版真机已确认 BBS 主站连接和登录成功；第六版真机已证明 WAP 完整登录不能接管 BBS；第七版已恢复 BBS 官方认证链并完成移动显示改造；第八版已限制 Cookie 只从 BBS 接管；第九版已修复登录竞态且真机确认普通板块可登录；第十版已隔离 BBS/水区 SID 并由真机确认普通区不再受水区影响；HAR 和只读重放已证明水区共享 BBS 认证且认证绑定 User-Agent；第十一版已统一并持久化 WebView/原生 UA，等待真机验证。
- [ ] 阶段五：新签名 release 已构建并通过静态签名/对齐校验；等待真机安装验证、密钥备份和 GitHub Release 发布。

开始每阶段前记录 `git status`，保留用户已有修改。保存基线 APK 和阶段 diff；需要回退时只撤销本次对应阶段的修改，不重置整个工作区。测试包并行安装可保留原应用；不要把覆盖安装后的版本降级或恢复登录会话视作可靠回滚方式。

下一步在测试机卸载旧作者签名的正式包后安装第十一版签名 release；若要保留旧版并行测试，可先使用 `.test` debug 包。安装后主动退出旧会话并重新完成一次 BBS 网页登录，使新 `tgc_auth` 与保存的 WebView User-Agent 匹配。登录窗口自动关闭并刷新普通区后，依次检查 `fid=25` 水区列表、水区帖子详情、切回普通板块；随后杀进程重启 App，再重复检查普通区和水区。预期整个过程只出现一次 BBS 登录/reCAPTCHA，水区首次请求自动建立自己的 SID，不再弹出第二次验证。真机通过并完成密钥加密备份后，提交源码和文档，创建 `v1.4.4` 标签并推送 `TGFC-own` 与标签，最后在 GitHub Release 上传签名 APK 和 SHA-256。含敏感 Cookie 的 HAR 只保留在本地并已由 `*.har` 忽略，不得提交或公开上传。

## 11. 返回导航行为修复（2026-09-11）

本轮修复主界面分区帖子列表的返回行为：

- 顶层 `ThreadListFragment`（无 Fragment back stack）按 Android 返回键时打开侧边菜单，不再结束 Activity。
- 开启“右划手势返回”后，顶层分区列表右划与返回键使用同一导航逻辑，因此打开侧边菜单；帖子详情、发帖和设置等二级页面仍按原规则回退。
- 设置项改名为“页面内右划返回”：它只控制 App 对页面内容区滑动的识别，不控制 Android 系统的屏幕边缘返回手势。关闭后，页面内右划不会触发应用导航；系统边缘手势仍按 Android 返回键处理，顶层会打开菜单。

验收时需要覆盖：顶层普通分区和水区的实体返回/右划、详情页返回/右划、设置页返回、侧边菜单打开时返回关闭菜单，以及关闭右划选项后的无动作行为。

本轮代码已通过 `assembleDebug` 和 `assembleRelease`。最新 debug APK SHA-256 为 `678E2C5FDEF850862B22AF08803E9B984724FAB320723F32C25038BD3A27AB64`，签名 release APK SHA-256 为 `EA5545A69C76F2F47A44711D1AF24E0C00D4A1526592E595856B57E9031ECB34`；release 的 v1/v2 签名和 zipalign 校验通过，尚待真机交互验收。
