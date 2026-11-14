# qqhook

Zygisk 模块：在 QQ、微信和抖音好友私信的聊天表情按钮上长按，打开本地图片面板。

各宿主分别保存自己的目录配置。所有宿主的 **QQ Zygisk → 表情目录** 都提供相同的三个入口：QQ Zygisk 图片目录、FunBox 表情目录、TGStickersExported 表情目录。三种来源都会被面板扫描；新建分类、保存图片使用第一个目录。

下表路径均相对于 `/storage/emulated/0/Android/media/`：

| 来源 | QQ 默认目录 | 微信默认目录 | 抖音默认目录 |
| --- | --- | --- | --- |
| QQ Zygisk 图片 | `com.tencent.mobileqq/.qqzygisk` | `com.tencent.mm/.qqzygisk` | `com.ss.android.ugc.aweme/.qqzygisk` |
| FunBox | `com.tencent.mobileqq/.fun/Sticker/Storage` | `com.tencent.mm/.fun/Sticker/Storage` | `com.ss.android.ugc.aweme/.fun/Sticker/Storage` |
| TGStickersExported | `com.tencent.mobileqq/TGStickersExported/v1` | `com.tencent.mm/TGStickersExported/v1` | `com.ss.android.ugc.aweme/TGStickersExported/v1` |

三类来源均可独立填写本地路径，或通过“选择目录”取得 SAF 目录授权。FunBox 分类名称优先读取授权范围内可访问的 `packs` 文件，无法读取时使用文件夹名。

**让微信只访问 QQ 的图片目录：**

1. 从微信内部进入 **设置 → QQ Zygisk → 表情目录**，不要在模块管理器或独立模块应用中授权；授权必须属于当前宿主 UID。
2. 在图片目录框填入 `/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqzygisk`，点击该字段旁的 **选择目录**。
3. 在系统目录选择器中确认选中的是 `.qqzygisk` 本身，再点 **使用此文件夹** 并允许访问；不要误选上级目录。
4. 返回后点击 **保存路径**，再重新打开图片面板。配置会保存系统返回的 `content://` 目录 URI，而不是将其转换成绝对路径读取。QQ、抖音及另外两类图片来源使用相同流程。

该流程不申请“管理所有文件”或额外媒体权限，仅新增所选目录及子目录的授权；宿主原有的其他权限不会自动撤销。授权属于整个宿主应用，不只属于模块代码。系统只授予读权限或提供方不支持写入时，仍可浏览、预览和发送，修改操作按能力限制；授权丢失时必须重新选择，不会静默退回旧路径。取消选择不会覆盖配置，改用默认路径后需保存才会释放不再使用的模块授权。

原生接口必须使用文件路径时，按需将请求的图片写入宿主私有 `files/qqzygisk/send-cache`；撤销目录授权不会回收已发送或已缓存的图片。平铺图片目录也会作为分类显示，但授权根目录本身不能删除。FunBox 迁移保留源文件；若来源与目标重叠，或提供方无法可靠确认目录关系，会拒绝迁移，避免向来源内部递归复制。

历史使用记录保存在当前宿主私有的 `files/qqzygisk/image-panel` 中，按来源身份（本地路径或授权 URI）区分；改选 URI 不会删除原路径的记录。本地路径模式继续兼容可访问的旧 `.usage.json`，后续更新只写私有存储；上次选择的分类通过宿主 SharedPreferences 保存。历史始终记录原始图片身份，不记录发送缓存路径。图片来源可只读，使用记录写入失败不会使已提交的发送报错、重试或导致宿主退出。

QQ 发送前通过统一会话接口实时解析当前联系人，兼容面板添加的关闭保护包装。聊天切换、根视图失效或面板关闭时仍拒绝发送，不使用全局缓存联系人作为兜底。会话不可用时日志会标出绑定、根视图和 AIO 数据就绪状态，不记录联系人标识。

QQ 防撤回拦截在线撤回通知，并在群聊离线同步批次中只过滤明确属于对方的撤回项，保留同批普通消息、自己的撤回和同步游标。提示生成前会在后台线程查询原消息：仅确认当前账号、会话、序号匹配且含普通消息内容时显示“已保留”、提供跳转并记录撤回标记；查询成功但没有原消息时显示“本机未找到原消息”，查询失败或超时则显示“未确认原消息”。QQ 被后台限制或断线时，若正文从未到达本机而服务器已撤回，模块不能仅凭撤回通知重建正文；“收到撤回提示”不等于“收到过原消息”。

QQ 的已定位类、构造器、方法和字段以可读 JSON 保存在 QQ 私有的 `files/qqzygisk/targets/`，不再持久化全量类名清单。设置入口、设置分组、聊天会话绑定和相机编辑器分别使用 `qq-settings.json`、`qq-settings-group.json`、`qq-aio-session.json`、`qq-camera-editor.json`。文件记录 `versionCode`、`versionName` 及目标类名、成员名、参数类型、返回类型等描述；只按 `versionCode + versionName` 判断是否复用，不检查 DEX 路径、大小或修改时间。同版本直接恢复目标，不重新筛选，也不在恢复失败时自动重新定位；首次没有该功能缓存或 QQ 版本变化时才定位并保存。删除对应 JSON 可在下一次冷启动时重新生成，不需要额外存储权限。

安装过程不会遍历或改写用户 `Android/media` 图库的属主、访问权限或 SELinux 标签。若遇到目录访问异常，应先检查当前宿主的 SAF 授权和实际错误，再针对性诊断，不默认递归执行 `chown`／`chmod`。

微信 8.0.79 及 **8.0.80（3220）** 的「设置」页提供 **QQ Zygisk** 入口，聊天表情按钮支持长按打开本地图片面板，并通过微信原生接口发送本地表情。8.0.80 同时适配传统上传队列和新版 `SendEmojiTask` 的发送完成确认。

微信面板默认使用原生表情发送：读取微信配置的表情宽高上限，超限 JPEG/PNG 仅生成等比例缩小的 PNG 发送副本，不改原文件；尺寸内文件保持原字节。GIF 不重编码或静图化，GIF/WEBP 超限时显示实际尺寸与上限并取消发送。调用微信本地表情导入接口后，按实际发送文件校验 MD5、缓存大小和 GIF 类型，再通过原生发送链路提交；临时缩放副本在原生导入后释放。微信确认入队后立即关闭面板，后台继续等待消息终态：成功才记录使用历史，失败或未确认仍会提示，不自动重试。入队前的导入或校验失败会保留面板，用户可在发送方式菜单中手动选择普通图片模式。

抖音适配版本为 **40.7.0（400701）**。仅在原生私信 `EmojiWidgetUI` 的表情按钮上注册长按；设置页使用原生 `CommonItemView` 插入 **QQ Zygisk**。抖音面板统一按普通图片消息发送。

点击图片后，模块准备本地文件并直接提交抖音原生图片任务，由抖音处理上传和发送。抖音表情消息构造、收藏创建、自定义上传和原图地址解析流程均已移除。旧版保存的表情发送偏好不再使用。模块仍会在提交前校验原聊天，避免页面关闭或切换后误发；GIF 是否保留动画由抖音原生图片流程决定。

更新时需整包重装模块 ZIP，按模块管理器提示重启手机后再启动对应宿主。本项目的本地测试和 APK 签名核对无法代替实际收发验证；适配其他抖音版本前需重新核对混淆接口。

更新弹窗中的 ZIP 下载由当前宿主进程流式读取，进度按实际接收字节更新；服务器不提供总长度时显示活动进度条，不估算百分比。关闭弹窗或离开设置不取消下载；宿主被强停或进程退出后，再打开设置会恢复未完成任务，有可靠验证器且服务器支持时断点续传，否则安全地从头下载。完成文件才发布到公共“下载”目录并提供“打开 ZIP”，不会自动安装。通知受宿主通知权限控制，不会额外申请宿主权限；旧版本尚未结束的系统下载会等待其结束后接管，若需取消该旧任务，请从系统下载通知操作。

模块管理器的 Web 设置页提供 QQ、微信、抖音三个独立 Hook 开关，分别控制对应宿主；修改后需重启对应应用生效。各开关独立加载并显示错误，修改失败时保留原状态。

本地验证与打包需要 JDK 21、Android SDK 36、Node.js 22 及 pnpm 11.9.0：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Gradle 在打包模块前自动按 `webroot/pnpm-lock.yaml` 安装前端依赖并构建 Web UI；缺少 `webroot/dist/index.html` 或其引用的 JS/CSS 时构建失败，不再依赖工作区残留的 `dist`。GitHub Actions 还会检查最终 ZIP 中的 `webroot/index.html` 及资源文件，发布附件使用该流水线生成的模块包。

SAF Android 端到端测试会操作真实系统目录选择器，并在 QQ 媒体目录内创建、清理独立测试子目录；请仅连接临时测试模拟器运行，模拟器需支持 `arm64-v8a` 或对应转译：

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.qm.qqzygisk.saf.SafDirectoryFlowTest
```

## 鸣谢

- [Hakunm](https://github.com/Hakunm)
- [QAuxiliary](https://github.com/cinit/QAuxiliary)
