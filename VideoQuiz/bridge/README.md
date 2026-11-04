# 卷灵 · 电脑端部署指南

手机 App（卷灵 / JuanLing）只是遥控器和显示器。**真正干活的是这台电脑**：
读视频、解题目、生成 PDF 都在本地跑，视频不会上传到任何第三方。

所以：**只装 App 是没用的**，电脑上必须把这套东西装齐。

---

## 一、前置条件清单

| # | 需要什么 | 检查方法 | 缺了会怎样 |
| --- | --- | --- | --- |
| 1 | **Node.js 20+** | `node --version` | 桥接启动不了 |
| 2 | **DSH**（DeepSeek Harness） | 浏览器能打开 `http://127.0.0.1:3080` | 没有解题的地方 |
| 3 | **会话管理插件**<br>`@zhuobichen/dsh-agent-supervisor` | `POST http://127.0.0.1:3080/dsh-agent-supervisor/command` 带 `{"op":"list"}` 有返回 | 手机端「选会话 / 建会话 / 派发 / 追问」全部不可用 |
| 4 | **本目录的桥接** `server.mjs` | 8765 端口在监听 | 手机传不上来 |
| 5 | **一个工作区**（如「视频成卷」） | 见第五节 | 任务会投到别的地方 |
| 6 | 手机与电脑同网 | 见第七节 | 连不上 |

> 第 3 项最容易漏。它是第三方插件，不是 DSH 内置能力——没装的话，手机端一切会话相关操作都是 404。

---

## 二、一键自检

装完或出问题时，双击：

```
preflight.cmd
```

它会逐项检查上面 6 条 + 防火墙，最后告诉你缺什么、怎么补。

示例输出：

```
1) Node.js 运行时
  [通过] Node.js v24.11.1
2) DSH（DeepSeek Harness）
  [通过] DSH 网页服务在 3080 端口监听
3) 会话管理插件
  [通过] 插件已加载，能列出 107 个会话
4) 桥接服务
  [通过] 桥接在 8765 监听，令牌有效
5) 手机连接用的地址
  [通过] IPv4：http://10.195.91.216:8765
  [通过] IPv6：http://[2400:...]:8765   固定地址（重启不变）
6) 目标工作区
  [通过] 已锁定工作区 视频成卷
7) Windows 防火墙
  [注意] 没有找到放行入站 8765 的规则
```

---

## 三、装插件（第 3 项）

插件包名 `@zhuobichen/dsh-agent-supervisor`，它给 DSH 增加了一个本地 HTTP 路由 `/dsh-agent-supervisor/command`，支持这些操作：

| op | 作用 |
| --- | --- |
| `list` | 列出会话 |
| `send` | 往指定会话投消息 |
| `start` | 建会话 + 投第一条 |
| `createSession` | 只建会话（可指定工作区、标题） |
| `createWorkspace` | 建工作区 |
| `tail` | 读会话最近消息 |
| `ensureFullAccess` | 把新会话默认权限设成完全权限 |

### 源码位置

公开仓库：**https://github.com/zhuobichen/zhaowen-dsh**

插件在 `packages/dsh-agent-supervisor/`，是纯 JS，不需要编译。

### 安装（Windows 示例）

```powershell
# 1. 拉仓库（随便放哪，下文用 D:/zhaowen-dsh）
git clone https://github.com/zhuobichen/zhaowen-dsh.git D:/zhaowen-dsh

# 2. 进 DSH 的 web profile
cd <DSH_HOME>/profiles/web

# 3. 挂成本地依赖（link: 指向刚才 clone 的目录）
#    也可以直接 npm install D:/zhaowen-dsh/packages/dsh-agent-supervisor
npm pkg set "dependencies.@zhuobichen/dsh-agent-supervisor=link:D:/zhaowen-dsh/packages/dsh-agent-supervisor"

# 4. 让 node_modules 里出现这个包（软链或复制都行）
npm install --no-audit --no-fund
```

### 在 cordis.yml 里注册

打开 `<DSH_HOME>/profiles/web/cordis.yml`，在插件列表里加两条：

```yaml
  - id: agent-supervisor
    name: '@zhuobichen/dsh-agent-supervisor'
```

（`id` 随便起，`name` 必须和包名一致。）

### 重启并验证

```powershell
# 重启 DSH，然后：
Invoke-RestMethod http://127.0.0.1:3080/dsh-agent-supervisor/command `
  -Method Post -ContentType application/json -Body '{"op":"list"}'
```

能返回 `{"ok":true,"count":N,"sessions":[...]}` 就装好了。

或者直接双击 `preflight.cmd`，第 3 项会显示结果。

> 这个路由只监听 127.0.0.1，不对外网开放。手机从来不直接访问它，只经由桥接转发。

---

## 四、启动桥接（第 4 项）

双击：

```
start.cmd
```

它会：

1. 设置 `VIDEOQUIZ_BIND_HOST=0.0.0.0`（手机走 IPv6，必须监听全部地址）
2. 设置 `VIDEOQUIZ_WORKSPACE_ID`（锁定工作区，防止投错）
3. 启动 `server.mjs`，默认端口 8765
4. 首次运行生成 `data/token.txt`——**这串就是手机的配对令牌**

窗口不要关，关了手机就连不上。

### 为什么要锁工作区

不锁的话，桥接会按「锚点会话」反推归属工作区，一旦那个会话换了位置，任务就会投到别的项目去。锁死后它只会在这一个工作区里投递，越界直接返回 403。

### 桥接是 DSH 的子进程

DSH 重启时桥接会一起退出（这是设计使然，不是 bug）。DSH 重启后重新双击 `start.cmd` 即可。

---

## 五、准备一个工作区

在 DSH 网页界面里新建一个工作区（例如叫「视频成卷」），然后在 `start.cmd` 里填它的 id：

```bat
set "VIDEOQUIZ_WORKSPACE_ID=<工作区 id>"
```

工作区 id 可以从 `$DSH_HOME/storages/workspace.json` 里找到，或者用：

```
POST /dsh-agent-supervisor/command  {"op":"createWorkspace","path":"D:/你的目录","title":"视频成卷"}
```

不填也能跑，但会退化成锚点反推模式。

---

## 六、默认权限（重要）

新会话如果默认是「工作区内修改」，那么写 PDF、跑脚本时 DSH 会**逐次弹人工批准**，整卷解析会卡住等你点。

两种解法，任选其一：

**A. 设一次全局默认**（推荐）

让 `ensureFullAccess` 写进设置：

```
POST /dsh-agent-supervisor/command  {"op":"ensureFullAccess"}
```

返回 `{"ok":true,"global":true}` 就成功了。之后新建的会话都是完全权限。

**B. 用环境变量**

启动 DSH 前设：

```
set DSH_PERMISSION_MODE=danger-full-access
```

`cordis.yml` 里的 sandbox 模式和审批策略都读这个变量。

> 注意：直接改 `cordis.yml` 里的字面量**靠不住**——DSH 会用 `cordis.patch.yml` 重新生成它，改动会被覆盖。要改就改 `cordis.patch.yml`，或者用上面两种方式。

---

## 七、手机怎么连

在手机 App 的「设置 → 电脑 Harness」里填：

| 字段 | 值 |
| --- | --- |
| 电脑地址 | `http://<IPv4>:8765` 或 `http://[<IPv6>]:8765` |
| 配对令牌 | `data/token.txt` 的内容 |

**IPv6 必须加方括号**：`http://[2400:6000:208:cdea:...]:8765`

### 用哪个 IPv6

一台机器常有多个 IPv6 地址，其中：

- `SuffixOrigin=Link` → **基于网卡固定，重启不变，用这个**
- `SuffixOrigin=Random` → 临时地址，重启就换，别用

`preflight.cmd` 会标出哪个是固定的。

### 校园网 / 公共 WiFi

这类网络常做**客户端隔离**（AP 隔离），手机和电脑虽然连同一个 WiFi，但互相不通：

- 症状：`ping` 不通、手机浏览器打不开、桥接日志里一条记录都没有
- IPv4 基本没救
- **IPv6 通常可以**（很多校园网只隔离 IPv4）

判断方法：看桥接窗口有没有手机来的请求日志。**一条都没有 = 根本没到电脑**。

---

## 八、接口一览（排错用）

所有接口除 `/api/health` 外都要 `Authorization: Bearer <token>`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查 |
| GET | `/api/sessions` | 当前工作区的会话 |
| POST | `/api/sessions/create` | 在工作区里建会话 |
| GET | `/api/jobs` | 所有任务及状态 |
| GET | `/api/jobs/:id/status` | 单任务状态 |
| POST | `/api/jobs/:id/forward?force=1` | 派发 / 强制重投 |
| POST | `/api/jobs/:id/cancel` | 取消 |
| POST | `/api/jobs/:id/resume` | 恢复 |
| GET | `/api/jobs/:id/result` | 取结果 |
| GET | `/api/jobs/:id/files/:name` | 下载结果文件 |
| GET/POST | `/api/jobs/:id/chat` | 追问（读最近消息 / 再发一条） |

### 任务状态含义

| 状态 | 含义 | 怎么办 |
| --- | --- | --- |
| `uploaded` | 已上传，未派发 | 点「开始处理」 |
| `processing` | 已派发给会话 | 等 |
| `complete` | 有结果 | 下载 |
| `stalled` | 派发过但会话没在跑，或超时无产出 | 「重新派发」 |
| `cancelled` | 已取消 | 「恢复」 |

`stalled` 的判定逻辑：目标会话不在运行中（且超过 3 分钟启动宽限），或超过 45 分钟没有任何文件变化。

---

## 八点五、判定外包（可选）

选择题占整卷大头，但逐题推理很慢很贵。可以把它外包给一个专用判定模型（Jev / typesafeai）：
只做"从选项里挑一个"，不给过程，但会返回校准过的置信度。

### 流程

```
DSH 读图 → 只输出 questions.json（结构化题干，不解题）
   ↓
桥接 POST /api/jobs/ask-jev → Jev 批量判定
   ↓
conf ≥ minConfidence  → decided：直接采用
conf <  minConfidence  → unresolved：回给 DSH 精解
   ↓
合并 → answers.pdf / answers_only.pdf / answers.txt
```

DSH 那边只需要按提示词执行三步：写 questions.json → `task.ps1 -Operation ask-jev` → 采用 decided、精解 unresolved。

### 配置

把判定模型的凭据放进 `bridge/data/jev.json`（这个目录已被 gitignore）：

```json
{
  "baseUrl": "https://api.302ai.cn/typesafeai/v1/systemone",
  "apiKey": "sk-...",
  "model": "jev-latest",
  "batchSize": 8,
  "stateCharLimit": 7000,
  "minConfidence": 0.6,
  "timeoutMs": 60000
}
```

没有这个文件时 `ask-jev` 返回 503，会话会退回「全部自己解」，不影响主流程。

### 实测（2026-11）

| 题型 | 题数 | 正确 | 置信度 |
| --- | --- | --- | --- |
| 英语理解 | 4 | 4/4 | 1.0 |
| 数学 / 逻辑 / 物理 / 语法 | 4 | 4/4 | 0.97–1.0 |
| 中文基础（错别字/修辞/语病/文学常识） | 5 | 5/5 | 0.95–1.0 |
| 中文词性辨析 | 1 | 猜对但 conf 0.27 | 被阈值筛出 |

5 题判定耗时 **636ms**，整卷 40 道选择题成本约 **$0.0004**。

### 材料是决定性因素（对照实验）

同一份英语 A 篇（原文 1540 字符）+ 3 道题，答案 B/A/C：

| 输入方式 | 高置信采用 | 置信度 |
| --- | --- | --- |
| 只给问题，不给原文 | 1/3 | 0.75 / **0.25** / **0.59** |
| 原文写进 `stem` | **3/3 全对** | 1.0 / 1.0 / 0.97 |

只给问题时它只能瞎猜（22 题 0.25），把阅读原文塞进题干后立刻变确定。
**所以 `questions.json` 必须把材料一起写进去**，否则外包等于白做。

### 常驻 OCR 服务（省掉重复预热）

RapidOCR 每次冷启动要付约 **50 秒** CUDA 初始化。一个任务里会话常要调好几次 OCR，
每次都重启进程就白付好几次。所以桥接会自动拉起一个常驻服务：

```
桥接启动 → 检测 8766 → 没有就 spawn ocr_server.py（后台预热）→ 就绪后复用
ocr.py   → 优先请求 8766 → 失败才回退本地加载
```

**实测对比**（同一批图片）：

| 方式 | 耗时 |
| --- | --- |
| 冷启动（含预热） | 约 53 秒 |
| 常驻服务 | **3.36 秒**（8 张） |
| 单帧稳定 | **400ms** |

关键设计：
- `detached: true` + `unref()`：OCR 服务独立于桥接，**桥接重启不会连带杀掉它**，
  50 秒预热只付一次
- 服务只监听 `127.0.0.1:8766`，不对外
- CUDA 失败自动回落 CPU（靠速度探测判定，provider 失败不会抛异常）
- 手动关闭：`curl -X POST http://127.0.0.1:8766/shutdown`

接口：

```
GET  /health   -> {"ok":true,"engine":"cuda","ready":true,"stats":{...}}
POST /ocr      -> {"paths":[...]} 或 {"dir":"..."} 或 {"images":[{"name":"a.jpg","b64":"..."}]}
POST /shutdown
```

### 成本与速度优化（不影响正确率）

真实任务实测（英语卷 45 题，7.4 分钟完成）暴露出读图是绝对大头：

| 阶段 | 耗时 | 占比 |
| --- | --- | --- |
| 抽帧 + 位移分析 | 61 秒 | 14% |
| **读图转录（38 次 read_image）** | **275 秒** | **62%** |
| Jev 判定 | 12 秒 | 3% |
| 解题 + 排版 PDF | 89 秒 | 20% |

38 次读的都是 1920×1080 的原始帧，每张约 **7000 tokens**，合计约 26.8 万 token。

#### 三项优化（实测验证）

1. **降分辨率到长边 1000**
   1920×1080 → 1000×562，实测文字、公式、中文注释全部清晰可读。
   token 从 7000 降到约 2900，**省 59%**。

2. **每页只读一次**
   切页完成后删掉原始帧，避免逐帧回查。这是最费钱的坏习惯。

3. **OCR 优先**
   RapidOCR 走本地、不耗 token、比读图快一个数量级。先全量 OCR，
   只有置信度低或版式不明的段落才读图核对。

#### 预期效果

| 项 | 现在 | 优化后 |
| --- | --- | --- |
| 读图次数 | 38 | 8–15 |
| 单张 token | ~7000 | ~2900 |
| 读图总 token | ~26.8 万 | 2.3–4.3 万 |
| 读图耗时 | 275 秒 | 45–90 秒 |
| 整卷总耗时 | 7.4 分钟 | **3–4 分钟** |

#### 为什么不做并行

并行读图也能提速，但需要换 agent preset、处理跨组边界和合并去重，
**对正确率有风险**。上面三项是零风险的，先做这个；不够快再考虑并行。

### 阈值选择（真实任务实测）

跑完整行测卷（130 题）后，用同一份 `questions.json` 复算不同阈值：

| 阈值 | 采用 | 正确 | 准确率 |
| --- | --- | --- | --- |
| 0.6 | 43 | 40 | 93.0% |
| 0.7 | 37 | 36 | 97.3% |
| 0.75 | 36 | 35 | 97.2% |
| 0.85 | 30 | 29 | 96.7% |
| **0.9** | **27** | **27** | **100%** |
| 0.95 | 23 | 23 | 100% |

错的那题 `conf=0.85`，所以默认阈值定在 **0.9**——宁可少判几题，也不往卷子里塞错答案。
另外同一个任务重跑判定，边缘题的结果会变（模型有采样随机性），所以阈值要留安全边际。

按题型统计 122 道行测题（threshold 0.75）：

| 题型 | 题数 | 可外包 | 覆盖率 | 外包准确率 |
| --- | --- | --- | --- | --- |
| 政治理论 | 20 | 19 | 95% | 100% |
| 言语理解 | 30 | 25 | 83% | 100% |
| 常识判断 | 15 | 11 | 73% | 91% |
| 判断推理（含图形） | 27 | 12 | 44% | 92% |
| 数量关系（要列式算） | 10 | 0 | 0% | — |
| 资料分析（缺数据表） | 20 | 0 | 0% | — |

后两类不是模型不行，是**输入不完整**：资料分析缺表格、图形题看不到图、数量关系要做多步计算。
标 `needsFigure: true` 的题会被桥接直接跳过，不浪费判定。

### 边界

- **不能读图**：state 只能是文本，前置的 OCR/读题仍然必须 DSH 做
- **不给过程**：只返回答案，PDF 里的解析还得 DSH 写
- **只做选择题**：blank / essay 类型原样交给 DSH
- 中文长文本推理较弱，靠 `minConfidence` 兜住

## 八点六、整段模式与分段模式（同一套试卷）

文哥口径（2026-11）：**用模式区分，不按段拆会话**。

| 模式 | taskMode | 适用 | 会话 |
| --- | --- | --- | --- |
| 整段模式 | `whole` | 一次提交完整材料（整段视频 / 整卷相片） | 一个会话 |
| 分段模式 | `segment` | 分多次提交（多段视频 / 逐张相片） | **同一套卷的所有分段共用一个会话** |

关键约束：

- 整段与分段**不共用会话**，避免上下文互相污染；
- 但分段**不是每段一个会话**——同一套试卷的所有段落在同一个会话里；
- 同一套试卷的多个分段**共享已出答案**，后一段不会重新识别前面已定的题；
- 同一试卷内**串行处理**（按入队 seq），不会两个分段同时抢一个会话；
- 不同试卷之间可以并行，互不阻塞。

### 接口

```http
POST /api/units?taskMode=segment&paperId=paper-1&mode=single
X-Unit-Kind: image            # image | video-segment
X-Unit-Name-B64: <base64>     # 文件名
X-Unit-Mode: single           # batch | single | video（调度时机）
X-Unit-Task-Mode: segment     # whole | segment（任务模式）
X-Unit-Paper: paper-1         # 试卷标识，不传默认 default
```

响应带 `hash / seq / paperId / taskMode / session / resultUrl / paperUrl`。

```http
GET  /api/papers/{paperId}    # 这套卷累积的答案 + 所有分段
GET  /api/units               # 队列摘要
GET  /api/mode                # 当前模式、可选模式、两个会话槽位
POST /api/mode {"mode":"single"} 或 {"taskMode":"segment"}
POST /api/units/flush?paperId=paper-1
```

### 配置两个会话

```cmd
set VIDEOQUIZ_WHOLE_SESSION_ID=session-xxx     :: 整段模式
set VIDEOQUIZ_SEGMENT_SESSION_ID=session-yyy   :: 分段模式（同一套卷的所有段都进这里）
```

两者都没配时回落到 `VIDEOQUIZ_TARGET_SESSION_ID`，此时启动会打一条 warn（因为整段和分段会混在一起）。

## 九、常见问题

**手机测试连接失败**
1. 桥接窗口还开着吗
2. 地址末尾 `:8765` 有没有丢
3. IPv6 有没有加方括号
4. 换 IPv4 / IPv6 另一种试试
5. 桥接日志里有没有请求记录（没有 = 网络层就没通）

**一直显示处理中**
去「任务」页看状态。`stalled` 就点「重新派发」。点过一次没用的，升级到 App v2.6+（旧版没带 `force`，会被桥接幂等跳过却返回 200）。

**写文件要我点批准**
见第六节。

**PDF 打不开**
先确认状态是 `complete`。App 内置阅读器解析失败会提示，可以重新下载。

**任务投到了别的会话**
说明没锁工作区。见第五节。

---

## 十、数据与隐私

- 视频、结果全部在 `bridge/data/jobs/<id>/`，不出本机
- 令牌在 `bridge/data/token.txt`，等同密码
- 令牌泄露：删掉 `token.txt`，重启桥接会生成新的，手机端重新填
- 结果文件在手机 App 私有目录，卸载才清除

---

## 文件清单

```
bridge/
  server.mjs        桥接服务本体
  start.cmd         启动（已带环境变量）
  preflight.cmd     前置条件自检
  preflight.ps1     自检脚本
  task.ps1          claim/complete 辅助（供会话内调用）
  allow-firewall.cmd 放行 8765（需管理员）
  data/
    token.txt       配对令牌
    jobs/<id>/      每个任务：视频、结果、状态标记
```
