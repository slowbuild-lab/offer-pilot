# 作战台 v2 实施计划

日期：2026-10-21。状态：**已按确认结果实施**（1 首页替换为作战台；2 不做未投递原因统计；3 预检 WARN 只用文字提示）。上一轮（多平台并行）见 [../docs/parallel-verification.md](../docs/parallel-verification.md)。实施结果与验证见文末。

## 一、目标

把现在这个"配置管理中心"变成"并行投递的作战台"：打开首页就知道**谁在跑、今天投了多少、哪里卡住了、下一步该点哪个按钮**。

本轮只做"看得见、点得动"的部分，**不动投递算法**：不改 BOSS/智联的选择器、不改打招呼语逻辑、不碰验证码、不自动聊天。数据库只允许新增表，不改已有表结构。

## 二、交付物总览

| 编号 | 交付物 | 解决的问题 | 价值 | 成本 | 依赖 |
|---|---|---|---|---|---|
| A | 作战台首页 `/` | 首页跳环境配置，看不到全局状态 | 高 | 中 | 现有 `/api/platforms/status` |
| B | 运行前预检 | 点"开始"之后才发现没登录/没配置/额度用完 | 高 | 低 | 现有 status + 配置接口 |
| C | 需要处理收件箱 | 验证码/登录失效/待核实散在各页 | 高 | 中 | 智联已有 attempts/resume |
| D | 未投递原因统计 | 只知道投了多少，不知道为什么没投 | 中 | 中高 | 需 worker 埋点（建议下一轮） |

**本轮不做**（避免一次改太多）：统一投递账本（BOSS 侧）、求职意向单一来源、跨平台去重、平台分工、桌面通知、结果漏斗。这些都要动 worker 或数据模型，放到 v3。

## 三、交付物 A：作战台首页

### 页面结构

```
┌ 顶部 KPI ────────────────────────────────────────────┐
│ 并行 1/2 · 今日投递 23 · 待核实 2 · 需要处理 3        │
├ 平台卡片（2×2）──────────────────────────────────────┤
│ BOSS直聘  运行中  已登录  浏览器已开  已投12  [停止]  │
│ 智联招聘  空闲    已登录  浏览器未开  —       [开始]  │
│ 猎聘      未验证  未登录  —                    [打开] │
│ 51job     未验证  未登录  —                    [打开] │
├ 最近任务 ────────────────────────────────────────────┤
│ 15:32 智联 暂停 · 连续 3 次结果未确认                  │
│ 15:10 BOSS 完成 · 发起 12 个聊天                      │
└─────────────────────────────────────────────────────┘
```

### 交互

- 每张卡片只出现当前可做的动作：`开始投递 / 停止 / 继续 / 打开浏览器`，按 `taskState` 切换，与平台页现有规则一致。
- 卡片底部一行显示 `lastMessage`（失败/暂停原因），不用跳页才能看到。
- 顶部提供**全部停止**（安全动作，允许）；**不做批量开始**（容易误触发两个平台同时投递）。
- 数据每 2 秒轮询一次，刷新页面后与后端一致。

### 新增接口

`GET /api/workbench/summary`（前端只打一个接口，避免首页拼 4 个请求）

```json
{
  "parallel": { "max": 2, "running": 1, "active": ["boss"] },
  "platforms": {
    "boss": { "taskState": "RUNNING", "isRunning": true, "isLoggedIn": true,
              "browserReady": true, "lastMessage": "", "deliveredCount": 12,
              "todayAttempts": null, "remainingToday": null, "verified": true },
    "zhilian": { "...": "同上，并带 todayAttempts/remainingToday/unknownCount" }
  },
  "today": { "delivered": 23, "unknown": 2, "remaining": 27 },
  "recent": [ { "at": 1792567970000, "platform": "zhilian", "state": "PAUSED", "reason": "连续 3 次未确认" } ]
}
```

数据来源：`PlatformTaskCoordinator.statuses()` + `PlaywrightManager.browserStatus()` + `ZhilianDeliveryLedger.summary()`；BOSS 没有账本，`todayAttempts/remainingToday` 返回 `null`，前端显示 `—`，**不编造数字**。

`recent` 首版从智联 `zhilian_tasks` 取（已有表）；BOSS 暂时没有任务历史，等 v3 统一账本后再补。

### 涉及文件

- 新增 `application/controller/WorkbenchController.java`
- 重写 `front/app/page.tsx`（现在是 redirect）
- 新增 `front/app/components/PlatformCard.tsx`、`RecentTaskList.tsx`
- 修改 `front/app/components/Sidebar.tsx`（加"作战台"，保留"环境配置"）
- 修改 `front/app/layout.tsx` 标题（"配置管理中心" → "求职作战台"）

### 验收

- 两个平台同时运行时，两张卡片都显示"运行中"，KPI 显示 `并行 2/2`。
- 停止某个平台后按钮变"正在停止…"，任务退出后才变"开始投递"。
- 刷新页面、换标签页打开，状态与后端一致。
- 没有任务时是空态，不是一片空白或假数据。
- 未登录/浏览器未开时动作是"打开浏览器"，不是灰色死按钮。

## 四、交付物 B：运行前预检

### 接口

`POST /api/platforms/{platform}/preflight`

```json
{
  "ready": false,
  "checks": [
    { "id": "login", "label": "登录状态", "status": "FAIL", "hint": "登录已失效，需要扫码", "action": "login" },
    { "id": "config", "label": "搜索关键词", "status": "PASS" },
    { "id": "quota", "label": "今日额度", "status": "WARN", "hint": "该平台未设置每日上限" },
    { "id": "parallel", "label": "并行额度", "status": "PASS" },
    { "id": "browser", "label": "浏览器", "status": "WARN", "hint": "启动后会自动打开" }
  ]
}
```

### 检查项

| id | 判断依据 | FAIL 条件 |
|---|---|---|
| login | `isLoggedIn(platform)` | false |
| config | 该平台配置的关键词/城市 | 关键词为空 |
| quota | 智联读 ledger 当日剩余；其它平台返回 WARN | 剩余为 0 |
| parallel | `PlatformTaskGate.snapshot()` | 该平台已占用，或全局已满 |
| browser | `browserStatus().browserReady` | 永远不 FAIL（未启动会自动拉起，判 WARN） |

### 前端接入

- BOSS / 智联页面点"开始投递"先调预检：全 PASS 直接启动；有 FAIL 弹清单，按 `action` 跳"打开浏览器登录"或"去配置"。
- 作战台卡片的"开始投递"同样先预检。
- WARN 不阻塞，但要在弹窗里显示原因。

### 兼容与风险

- 预检是**只读**接口，不启动浏览器、不写库；`/start` 原有的校验全部保留，预检只是把错误提前。
- 预检与真正启动之间状态可能变化（比如另一个平台刚好占满），`/start` 仍会返回 409，前端按现有逻辑提示。

### 涉及文件

- 新增 `worker/service/PlatformPreflightService.java`、`application/controller` 里挂一个 `POST /api/platforms/{p}/preflight`（并入 B 的控制器）
- 新增 `front/app/components/PreflightDialog.tsx`
- 修改 `front/app/boss/page.tsx`、`front/app/zhilian/page.tsx` 的启动入口

### 验收

- 未登录：FAIL，弹窗能直接打开对应浏览器。
- 关键词为空：FAIL，提示去配置。
- 两个平台已占满：FAIL，提示"并行上限 2，请先停止一个"。
- 全部通过：不弹窗，直接启动。

## 五、交付物 C：需要处理收件箱

聚合四类"卡住等人工"的事件：

1. 智联待核实投递记录（`/api/zhilian/attempts/unknown`，已有）
2. 智联暂停任务（`taskState=PAUSED`，已有 `/api/zhilian/resume`）
3. 任一平台登录失效（`isLoggedIn=false` 且该平台有历史任务）→ 动作"打开浏览器登录"
4. 任一平台最近消息是 error/warning（验证码、平台上限）→ 展示原因

`GET /api/workbench/attention` 返回统一列表，每项带 `platform / kind / message / action`。处理动作全部复用现有接口（resolve / resume / login），不新增写操作。

**局限（要写进界面文案）**：BOSS 目前没有待核实记录，收件箱里的 BOSS 条目只能提示"登录失效/异常消息"，不能逐条核实。

验收：智联造一条待核实 → 出现在首页；登记后消失；点"继续"走原 resume；无事项时显示"暂无需要处理"。

## 六、交付物 D：未投递原因统计（建议下一轮）

现状没有任何"为什么没投"的数据。要做需要两件事：

1. 智联：`ZhilianDeliveryLedger` 已有 `DUPLICATE / TASK_LIMIT / DAY_LIMIT`，可直接按天统计，**不需要改 worker**。
2. BOSS：要在 `Boss.java` 的"跳过/按钮不可用/黑名单"分支埋点，属于改投递代码，风险高于本轮其它三项。

因此建议：本轮只做**智联原因统计**（只读现有表，零风险），BOSS 放到 v3 与统一账本一起做。是否本轮做，见"需要你确认"。

## 七、实施顺序（每步可单独验收）

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1 | 后端 `summary` + `preflight`（只读） | curl 返回结构正确；不改任何投递行为 |
| 2 | 作战台首页 + 侧边栏 | 两平台并行时卡片/KPI 正确；刷新一致 |
| 3 | 预检弹窗接入 BOSS/智联 | 三种 FAIL 场景都能复现 |
| 4 | 收件箱 | 智联待核实/暂停都能处理 |
| 5 | 构建 + 本地隔离验证 | `gradlew build`、`next build`；用临时库跑，不投真实简历 |

每步完成后我都会重新构建，并在最后给一份"实际验证记录"，不把这次的计划当结果。

## 八、风险与不做的事

- 首页从"环境配置"变成"作战台"是导航变更，环境配置入口保留在侧边栏，不影响现有功能。
- 静态导出（`output: 'export'`）下全部用客户端 fetch，不做 SSR。
- 不动 `PlatformTaskGate` / 并行上限 / 会话隔离；不改 `db/getjobs.db` 已有表。
- 不做批量开始、不做自动重试、不做定时任务、不做通知（v3）。

## 九、需要你确认的 3 件事

已确认：

1. **首页直接替换成作战台** —— 已替换，环境配置保留在侧边栏。
2. **第 D 项（未投递原因）本轮不做** —— 未实现，也没有新增相关表或埋点。
3. **预检 WARN 只文字提示** —— 只有 FAIL 会阻止启动；WARN 在页面顶部用一段文字说明原因。

## 十、实施结果（2026-10-21）

### 新增

- `application/controller/WorkbenchController.java`：`GET /api/workbench/summary`、`GET /api/workbench/attention`。
- `worker/service/PlatformPreflightService.java`：登录、投递条件、今日额度、并行占用、浏览器五项检查。
- `PlatformStatusController` 增加 `POST /api/platforms/{platform}/preflight`、`POST /api/platforms/stop-all`。
- `ZhilianDeliveryLedger.recentTasks(int)`：作战台"最近任务"只读查询。
- `PlaywrightManager.isBrowserReady(String)`。
- 前端：`app/page.tsx`（作战台）、`components/PlatformCard.tsx`、`components/PreflightDialog.tsx`、`lib/preflight.ts`；侧边栏新增"作战台"。

### 修改

- `Sidebar.tsx`：新增"总览 / 作战台"分组，副标题改为"求职作战台"。
- `layout.tsx`：页面标题改为"求职作战台"。
- `boss/page.tsx`、`zhilian/page.tsx`：开始投递前先预检，FAIL 弹清单、WARN 只显示文字。

### 实测（临时数据库副本，未改动 `db/getjobs.db`）

- `GET /api/workbench/summary`：`maxParallel=2`，四个平台状态与浏览器状态齐全，`today` 返回智联当日尝试/剩余，`recent` 从智联任务表读取。
- 四个平台 `preflight`：BOSS 因关键词为空返回 FAIL（config），智联全通过仅浏览器 WARN，猎聘登录与配置双 FAIL，额度项对无上限平台返回 WARN。
- 插入一条 UNKNOWN 记录后，`attention.count=1`、条目含岗位/公司/原因与 `open_zhilian_attempts` 动作；删除后恢复 0。
- `POST /api/platforms/stop-all` 无任务时返回 `count=0` 与明确文案。
- 首页 `/` 已是作战台（静态产物包含"求职作战台""并行运行"）。
- `next build`、`gradlew bootJar` 通过；真实库 md5 前后一致（`a1950ed0…5547`）。
