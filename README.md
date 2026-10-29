# Android Apps

我的安卓应用开发仓库。每个 App 一个子目录，各自独立构建。

| App | 说明 | 技术栈 | 状态 |
|---|---|---|---|
| [MapMate](MapMate/) | 会说话的手机地图：高德地图 + DeepSeek Agent，主打骑行导航与记录 | Java · Material3 · 高德 SDK 10.0 · DeepSeek API | 自用可用 |
| [卷灵 / JuanLing](VideoQuiz/) | 拍一段试卷录像 → 交给自己的电脑解析 → 回传答案与 PDF（原 VideoQuiz） | Java · Material3 · 本地 DSH 桥接 · PdfRenderer | v3.0 |

## 下载

APK 在 [Releases](https://github.com/zhuobichen/AndroidApps/releases) 页面。

> **卷灵只装 APK 不能用**：真正读题、解题、排版 PDF 都在你自己的电脑上跑，
> 电脑端需要 Node.js + DSH + 一个第三方插件 + 桥接服务。
> 完整部署步骤与自检脚本见 [VideoQuiz/bridge/README.md](VideoQuiz/bridge/README.md)。
>
> MapMate 则需要在 App 内填 DeepSeek Key（或自建小智服务端），以及高德 Key。

## 两个 App 的形态不一样

| | MapMate | 卷灵 |
| --- | --- | --- |
| 算力在哪 | 手机 + 云端 API（或自建语音服务端） | **电脑本地**，手机只是遥控器 |
| 联网要求 | 需要外网访问模型 API | 电脑与手机同一局域网即可，视频不出本地 |
| 首次准备 | 填高德 Key + DeepSeek Key | 电脑端装 5 件套（见部署指南） |
| 典型耗时 | 秒级对话 | 整卷解析十几分钟，可后台跑 |

## 结构

```
AndroidApps/
├── MapMate/                  # 会说话的手机地图
│   ├── app/                  # 应用模块
│   ├── docs/                 # 截图
│   └── README.md             # 架构、坑、版本记录
├── VideoQuiz/                # 卷灵（原 VideoQuiz）
│   ├── app/                  # 应用模块
│   ├── bridge/               # 电脑端桥接服务 + 部署指南 + 自检脚本
│   ├── dist/                 # 构建产物（不进版本库）
│   └── README.md             # 架构、流程、版本记录
├── emuhome/                  # 模拟器工作目录
└── README.md                 # 本文件
```

## 构建

两个项目都用 JDK 21 + Android SDK 35 + Gradle 8.9：

```bat
set JAVA_HOME=D:\Tools\jdk-21.0.12.1+1
set ANDROID_SDK_ROOT=D:\Android\MapMate\MapMate\sdk

cd MapMate
D:\Android\MapMate\MapMate\tools\gradle-8.9\bin\gradle.bat assembleDebug

cd ..\VideoQuiz
D:\Android\MapMate\MapMate\tools\gradle-8.9\bin\gradle.bat assembleDebug
```

产物在各自的 `app/build/outputs/apk/debug/`。

## 发布

APK 不进版本库（`.gitignore` 排除 `dist/`），走 GitHub Releases：

```bash
gh release create v3.0 VideoQuiz/dist/juanling-v3.0-debug.apk \
  --title "卷灵 v3.0" --notes-file notes.md
```

目前是 **debug 签名**：能装、能覆盖升级，但不能上架商店。
要正式分发需要单独生成 release keystore。

## 新增一个 App

1. 在本目录下新建子目录（如 `Foo/`），用 Android Studio 新建工程或直接放 Gradle 项目
2. 项目自己的 `.gitignore` 写好（`build/`、`local.properties`、`*.keystore`、`dist/`）
3. 更新上面的表格

## 约定

- **密钥不进仓库**：高德 Key、DeepSeek Key、桥接配对令牌都在本地配置或 App 设置里填
- **截图**放各 App 的 `docs/` 下
- **构建产物**放 `dist/`，不提交
- 每个 App 的 `README.md` 记录它自己的架构、踩过的坑和版本变更
