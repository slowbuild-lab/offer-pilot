# HR 冷却期：开工前证据清单

2026-10-28。第二批选定范围：**HR 级冷却期**（不做公司接触上限）。本文只记录**已在源码中核实到的事实**和**需要你拍板的口径**，不含实现。

状态：待确认口径后开工。所有行号基于当前工作区（HEAD `eabd997` + 未提交改动）。

## 一句话结论

HR 稳定标识 `encryptUserId` **已经采到了**，但被丢在采集层，没进统一候选；投递账本以 `job_id` 为准，所以**同一个 HR 换个岗位就能再投一次**——这个漏洞当前确实存在，且有明确的单点可以堵。

## 1. 字段级证据

| 字段 | 采集处 | 是否进入统一候选 `JobCandidate` | 结论 |
|---|---|---|---|
| `encryptId`（岗位标识） | `Boss.java:512`（详情解析）、`Boss.java:405`（采集循环） | ✅ `jobId` | 已用于账本去重 |
| `encryptUserId`（HR 标识） | `Boss.java:513-521`，三级兜底：`jobInfo` → `bossInfo.encryptUserId` → `bossInfo.encryptBossId` | ❌ **没有** | 有值但被丢弃 |
| `activeTimeDesc`（HR 活跃） | `Boss.java:546`（存库）、`Boss.java:419`（循环内） | ❌ 原文没进，只被拼进一段原因字符串（`Boss.java:431`） | 无法在规则层判断 |
| `brandName`（公司名） | `Boss.java:538`、`Boss.java:424` | ✅ 有，但只是字符串 | 无 `brandId` |
| `bossInfo.name`（HR 名） | `Boss.java:544` | ❌ 没有 | 只能当展示，不能当键 |

`JobCandidate` 目前只有 9 个组件（`JobCandidate.java:4`）：platform / jobId / title / company / city / salary / link / description / platformFilterReason。**HR 维度的信息一个都没有。**

一个旁证：`boss_job_data` 入库时已经按 `(encrypt_id, encrypt_user_id)` 去重（`Boss.java:578`），缺 HR 标识才退化为只按岗位去重（`Boss.java:580`，`existsBossJobByEncryptId`）。说明作者本来就认为 HR 标识是可用且需要参与判定的，只是没把它抬到规则层。

## 2. 关键代码坐标

### 候选构造点（4 处，改字段时都要覆盖）

| 位置 | 场景 | 备注 |
|---|---|---|
| `Boss.java:433` | BOSS 采集循环（预览+执行都用） | 此处 `jobInfo`/`brand`/`boss` 三个 JSONObject 都在作用域内（`Boss.java:400-402`），**加 hrId 最省事** |
| `Boss.java:1047` | 预览分支的候选 | 7 个字段 + description |
| `Boss.java:1060` | BOSS 执行路径的候选 | 从这个对象进账本 |
| `ZhiLian.java:481` | 智联 | 同样没有 HR 标识 |

### 外发闸门（单点，设计上应复用）

| 位置 | 作用 |
|---|---|
| `JobProfileRuntime.java:47-52` `guard(snapshot, job)` | 外发前总闸：规则 + 紧急禁止 + 图片材料校验 |
| `JobProfileService.java:101-108` `assertAllowed` | 规则 + `delivery_forbidden` 表查询，命中抛异常 |
| `Boss.java:1138-1142` `checkExternal()` | 每个"待发动作"前调用 `externalGuard` |
| `BossJobService.java:120` / `ZhilianJobService.java:162` | 把 `guard` 接成 worker 的 `externalGuard` 回调 |

**这是这次设计能收敛的关键**：`guard` 已经是唯一的"发出去之前"通道，冷却期挂在这个通道上就能覆盖所有外发动作，不需要在每个点击点各加一遍。（BOSS 生效；智联是否启用见 §3 的 D。）

### 预览侧（要单独加，因为预览不走 guard）

| 位置 | 作用 |
|---|---|
| `JobProfileService.java:63` | 预览时逐岗跑规则 `rules().match(job)` |
| `JobProfileService.java:64` | **已有先例**：这里额外查一次 `delivery_forbidden` 表，命中就打 `FORBIDDEN` 检查项 |

冷却期照这个模式加即可，预览和台账共用同一套判断。

### 账本（当前为什么拦不住同 HR 多岗位）

| 位置 | 事实 |
|---|---|
| `BossDeliveryLedger.java:24` | `boss_attempts` 字段：id / task_id / **job_id** / title / company / link / state / reason / initiated_at / local_day，**无 hr_id** |
| `BossDeliveryLedger.java:53` | 去重条件 `WHERE job_id=? AND state!='RELEASED'` —— 只按岗位 |
| `BossDeliveryLedger.java:48` | `reserve(task, job, title, company, link)` 签名里没有 HR 参数 |
| `BossDeliveryLedger.java:55-56` | 任务上限、当日额度检查 |
| `Boss.java:1084` → `1087` → `1089` → `1090` | 预留 → 标记 SENT → 动作前再查 → 点击 |

### 现有"活跃过滤"的真实水平

`Boss.java:563-569`：`config.getFilterDeadHR()` 为真且 `activeTimeDesc.contains("年")`（`Boss.java:566`）就打 `已过滤`。它是**旧平台配置**里的字符串粗判，不在统一规则里，所以在方案页看不到、也调不了。第一批把它作为"原平台配置"原因透出（`JobCandidate.platformFilterReason`）。

## 3. 需要你拍板的四个口径

### A. 取不到 HR 标识时怎么办

代码里三级兜底（`513-520`）说明作者也遇到过字段位置漂移，**真实覆盖率我没法验证**（需要真实账号跑一次采集，我没操作）。

- 选项 1（建议，与第一批一致）：**缺 HR 标识不放行**，打"无法核验 HR 冷却期"并跳过。第一批的契约就是"条件启用时缺少 JD 不放行"。
- 选项 2：放行但标记为"未核验"。风险是冷却期在字段缺失时静默失效，等于没做。

### B. 什么时候记一次"接触"

账本有 `RESERVED → SENT → CONFIRMED/UNKNOWN` 三态，写成表：

| 时机 | 是否计次 | 理由 |
|---|---|---|
| `RESERVED`（`1084`，还没点） | ❌ 不计 | 预留了没发出去，计了会误伤 |
| `SENT`（`1087` `startAction`） | ✅ 计 | 动作已发出 |
| `UNKNOWN`（结果待核实） | ✅ 计 | 可能已经发了，保守计 |

你的选择会影响"点了但页面没确认"这类边界，我默认按上表。

### C. 冷却期默认天数

建议 **30 天**，可配 1–365，默认值写进方案页并显示；老方案（没有该字段）视为**未启用**，行为零变化。

### D. 智联要不要一起

`ZhiLian.java:481` 的候选同样没有 HR 标识。建议**本轮只做 BOSS**，与第一批同一套平台范围校验（选了非空条件但没选 BOSS 就不许保存）。要不要顺带给智联补字段，你定。

## 4. 我不会做的事（避免范围膨胀）

- **不做公司接触上限**：没有 `brandId`，只能按 `brandName` 字符串匹配，同名公司/集团多主体会误伤，而误伤的代价是"该投的被永久封"。除非你接受，否则推到第三批。
- **不改账本现有语义**：接触记录单独建表，不改 `boss_attempts` 的额度与 UNKNOWN 保护逻辑。
- **不改旧平台配置**：不动 `Boss.java:569` 那段旧活跃过滤，避免影响存量行为。

## 5. 验收怎么证明（可执行，不碰真实账号）

1. 同 HR 两个不同岗位：第二个被拦，`reason` 与检查项可读。
2. 不同 HR 不误伤（构造两个不同 hrId 的候选）。
3. 重启服务后再跑：仍然被拦（证明查的是表不是内存）。
4. 跨日：冷却期内仍拦（证明不按 `local_day` 算）。
5. 切换方案：新方案若沿用冷却期仍拦；关闭冷却期则放行（证明按方案取值）。
6. 缺 hrId：按 A 的选择验证 fail-closed。
7. 原保护不变：停止、紧急禁止、额度、UNKNOWN 不重投的既有 54 项测试全部仍通过。

## 6. 我没有验证的部分（如实说明）

- 真实 BOSS 响应里 `encryptUserId` 的**实际覆盖率和稳定性**——需要真实账号跑一次采集，本轮未做。
- `activeTimeDesc` 的取值枚举（"今日活跃/3日内活跃/半年前活跃"等）——代码里只按 `contains("年")` 粗判，真实枚举未核对。
- 智联是否有等价的稳定 HR 标识——未查证。
