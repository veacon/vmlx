# vmlx tv（am_atv 落雪音源线）

Android TV 音乐应用，基于 am_atv 0.6.2 上游改造，走落雪（LX）直链音源（Media3 ExoPlayer），不依赖 Apple 账号。

> 包名 `dev.vmlx.tv` ｜ 应用名 `vmlx tv` ｜ 当前版本 **v0.5**（versionCode 5 / versionName 0.5）

---

## 当前进度

| 版本 | 内容 | 状态 |
|---|---|---|
| v0.1 | 音源接入（LX 完整形态） | ✅ 已出包 |
| v0.2 | 播放体验与歌词（第三方五源级联） | ✅ 已出包 |
| v0.3 | 用户反馈修正（繁转简、LRC 过滤、导航栏动态图标） | ✅ 已出包 |
| v0.4 | 用户反馈修正（EqBars 右移、跑马灯歌手名、音源兜底、启动预加载） | ✅ 已出包，**待自测** |
| v0.5 | 逐字歌词（LRC 行级时间轴逐字点亮，参照 veil.music WordWipeLine） | ✅ 已出包，**待自测** |
| v0.6 | 本地资料库（LocalLibraryStore + PlaylistImporter + 最近播放本地化） | ⏳ 排期中 |

---

## 目录结构

```
am_atv-0.6.2/
├── app/
│   ├── build.gradle.kts          # 版本、依赖、签名配置
│   ├── libs/
│   │   ├── mediaplayback-1.1.1.patched.aar   # Media3 补丁包（必须保留）
│   │   └── musickitauth-1.1.2.patched.aar    # MusicKit 认证包（必须保留）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/dev/amatv/
│       │   │   ├── i18n/              # 简繁转换（TraditionalChinese.kt）
│       │   │   ├── integration/       # Apple 元数据、LX 播放桥、第三方歌词、MusicKit
│       │   │   ├── luoxue/            # LX 音源引擎（脚本、搜索、匹配、播放）
│       │   │   ├── model/             # 数据模型（LyricLine、LyricWord、Track 等）
│       │   │   ├── playback/          # 播放队列、媒体会话
│       │   │   ├── ui/                # Compose UI（AtvApp、PlayerScreen 等）
│       │   │   ├── veilsource/        # 匿名数据层（amp-api）
│       │   │   └── ...
│       │   ├── res/                   # 资源（图标、布局、字符串）
│       │   └── assets/                # LX 脚本引擎（QuickJS 字节码）
│       ├── debug/
│       ├── benchmark/
│       └── test/
├── benchmark/                    # 基准测试
├── buildSrc/                     # 构建辅助
├── docs/                         # 文档与截图
├── tools/                        # 开发脚本（导出 token、构建 Gecko 等）
├── gradle/wrapper/               # Gradle Wrapper
├── gradlew / gradlew.bat
├── build.gradle.kts              # 根构建脚本
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
└── developer.env.example         # 开发环境配置模板
```

---

## 编译环境

换机后需先配置以下路径（各机器不同，参照本地实际位置调整）：

| 项 | 示例（原开发机） | 说明 |
|---|---|---|
| JDK | `C:\devtools\jdk-21.0.2` | 必须 JDK 21；`JAVA_HOME` 指向它 |
| Android SDK | `C:\devtools\android-sdk` | `ANDROID_HOME` + `ANDROID_SDK_ROOT` 同时设 |
| build-tools | `35.0.0` | SDK 内自带 |
| Gradle 缓存 | `F:\AI\bam\.gradlehome` | **必须**设 `GRADLE_USER_HOME`，否则重下全部依赖 |
| 依赖镜像 init | `F:\AI\bam\init-deps.gradle` | 阿里云优先，官方源兜底；编译时 `-I` 引入 |
| 工程目录 | 本仓库根目录 | — |

### 编译命令

```bash
# 第一步：快速语法/类型检查（增量约 1 min）
gradlew.bat :app:compileDebugKotlin --console=plain

# 第二步：全绿后出包（全量约 4 min）
gradlew.bat :app:assembleDebug --console=plain

# 产物
app\build\outputs\apk\debug\app-debug.apk
# 复制为
F:\AI\vm\.apk\vmlx-YYYYMMDD-<版本>-lx-debug.apk
```

---

## 关键文件速查

### 音源链路（LX 直链）
- `luoxue/LxPlayback.kt` — 播放引擎、负结果短缓存（TTL 30s）、缓存管理
- `luoxue/PlatformSearch.kt` — 三平台并发搜索，query 用 `TrackMatcher.canonicalArtist()` 归一
- `luoxue/TrackMatcher.kt` — 艺人别名映射 + 繁转简归一，`rankByDurationOnly` ±8s 阈值
- `luoxue/LxBench.kt` — 脚本执行、`musicInfoJson` 生成
- `integration/LxPlayerBridge.kt` — Media3 ExoPlayer 桥接，自动换源传 `excludeScript`

### 歌词链路
- `integration/ThirdPartyLyrics.kt` — 网易云→QQ→酷狗→酷我→咪咕 五源级联 LRC 解析
- `ui/PlayerScreen.kt` 中 `WordWipeLine` — 逐字高亮（`estimateCharSpans` 按 CJK 字符/西文单词均分行级时间）
- `ui/PlayerScreen.kt` 中 `MarqueeText` — 歌手名 > 6 字跑马灯（`TextMeasurer` 预测量 + `graphicsLayer.translationX`）

### 启动预加载
- `ui/AtvApp.kt` 中 `buildShelvesForDestination()` + `startShelfPreload()` — 并发预取 Home/Browse shelves，写入 `shelvesByDestination` 缓存

---

## 注意事项

### 签名（覆盖安装生命线）
- 统一走 `assembleDebug`，使用 Android Debug 证书
- SHA-1：`4a03946a15a82ca52b11660ba8d2e4a9b6e47337`
- **禁止**改用其他 buildType 或自定义签名，否则老包无法覆盖升级，用户丢数据
- 已实测 v0.1 → v0.2 → v0.3 → v0.4 → v0.5 全部同一证书指纹

### 文件编码
- PowerShell 5.1 的 `Get-Content`/`Set-Content` 默认 ANSI，批量文本替换**必须**用 `.NET` `ReadAllText`/`WriteAllText`（UTF-8 无 BOM）
- 源码为 UTF-8，勿用 PS5.1 管道直接处理

### 歌词缓存
- key = `lyricsCacheKey(identity, "zh-single")`，variant 固定 `zh-single`（本项目只有简体版）
- 换源/换逻辑后需清缓存才能生效

### 独立 OkHttpClient
- `ThirdPartyLyrics` 自带独立 OkHttpClient，**绝不能**共享 Apple 的 Authorization/Cookie 拦截器

### Transliterator 线程安全
- `android.icu.text.Transliterator` 实例非线程安全，`TraditionalChinese.toSimplified` 已包锁，勿绕过

### LRC 头部元信息
- LRC 头部「词：X」「编曲：X」等行必须过滤（`LyricsLrc.creditLineRe`），否则会显示成前 30 秒歌词

### storefront
- `AM_STOREFRONT=tw` 导致元数据繁体属数据源特性；显示层已统一繁转简，勿在数据层乱改 storefront（会影响曲库匹配）

### 依赖版本
- 新增依赖先对齐现有 BOM/Media3 主版本，避免 Media3 被 geckoview 传递依赖顶掉

---

## 下一步（v0.6 排期）

1. **本地资料库**：
   - 移植 `LocalLibraryStore.kt`（本地歌曲/歌单持久化）
   - 移植 `PlaylistImporter.kt`（QQ/网易云/酷狗链接导入，纯 HTTP 无需登录）
   - 最近播放本地化（替换 Apple recentlyPlayed 链路）
   - Library 页改版：歌曲列表 + 最近播放 + 「添加链接」入口

2. **待自测项**（v0.4 + v0.5）：
   - 导航栏 EqBars 在「正在播放」**右侧**
   - 歌手名 > 6 字跑马灯完整显示，不截断不省略
   - 周杰伦《落雪》首次点播命中，负缓存 30s 生效
   - 冷启动 Home/Browse 无 loading 动画
   - 逐字歌词：中文按字点亮、西文按词点亮，暂停/seek 同步

---

## 参考文档（本地，不在仓库内）

- 项目进度表：`F:\AI\vm\repo-analysis\am-atv-项目进度表.md`
- 落雪命中优化核对报告：`F:\AI\vm\repo-analysis\am-atv-v0.4-落雪命中优化核对报告.md`
- veil.music 参考工程：`F:\AI\vm\repo-analysis\veil.music\veil.music-main\`
