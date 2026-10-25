# Android Apps

我的安卓应用开发仓库。每个 App 一个子目录，各自独立构建。

| App | 说明 | 技术栈 |
|---|---|---|
| [MapMate](MapMate/) | 会说话的手机地图：高德地图 + DeepSeek Agent，主打骑行导航与记录 | Java · Material3 · 高德 SDK 10.0 · DeepSeek API |
| [VideoQuiz](VideoQuiz/) | 把试卷录像转成题目 + 答案的 PDF：抽帧 → 送多模态模型读题 → 解题 → 导出 | Java · Material3 · DeepSeek 多模态 API |

## 结构

```
AndroidApps/
├── MapMate/                  # 工程根（可单独用 Android Studio 打开）
│   ├── app/                  # 应用模块
│   ├── docs/                 # 截图
│   ├── README.md             # 该 App 的详细说明与开发记录
│   └── build.gradle.kts
└── README.md                 # 本文件
```

## 新增一个 App

1. 在本目录下新建子目录（如 `Foo/`），用 Android Studio 新建工程或直接放 Gradle 项目
2. 项目自己的 `.gitignore` 写好（`build/`、`local.properties`、`*.keystore`）
3. 在上面的表格里加一行

## 约定

- **密钥不进仓库**：高德 Key、DeepSeek Key、WebDAV 密码都在本地配置或 App 设置里填
- 截图放各 App 的 `docs/` 下
- 每个 App 的 `README.md` 记录它自己的架构、坑和版本变更