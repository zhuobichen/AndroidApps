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
