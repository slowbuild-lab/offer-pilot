# boss-helper 第二批实施：HR 冷却期

2026-10-28。用户确认范围：**只做 HR 级冷却期**，不做公司接触上限（缺 `brandId`，只能按公司名匹配，误伤代价过高）。

口径（已按证据清单 §3 的建议执行，未另行更改）：

- **A 缺 HR 标识 → 不放行**：打 `BOSS_HR_COOLDOWN` 检查项并跳过，与第一批"缺 JD 不放行"一致。
- **B 计次时机**：`RESERVED` 不计；`SENT` 与 `UNKNOWN` 都计（未知动作可能已被看到）。
- **C 天数**：默认不启用（留空 = 老方案行为零变化），可配 1–365。
- **D 平台范围**：仅 BOSS；选了非空冷却期但未选 BOSS 平台时保存直接拒绝。

## 合同

- `JobProfile.contactPolicy.hrCooldownDays`：可选整数，`null` 表示未启用。
- 命中冷却期只影响评估，不占投递额度、不写投递账本、不代表已投递。
- HR 标识取 `encryptUserId`，三级兜底 `jobInfo` → `bossInfo.encryptUserId` → `bossInfo.encryptBossId`。
- 接触记录按 `(platform, hr_id, job_id)` 幂等；同 HR 换岗位仍受控。
- 外发前仍校验停止、紧急禁止、额度、UNKNOWN 保护；新增条件不替代这些保护。

## 切片与落点

1. **候选事实**：`JobCandidate` 加 `hrId`、`hrActiveRaw`（`withHr` 组装，旧构造保留）；`Boss.java` 采集循环解析并带入；`Job.java` 加 `hrId` 供执行路径使用。
2. **台账**：新增 `ContactGuard`，表 `boss_contacts(platform,hr_id,job_id,contacted_at)`，独立于 `boss_attempts`。
3. **规则接入**：`JobProfile.Match.with(check)` 支持追加库侧判定；预览在 `JobProfileService.recordPreview` 打检查项；外发在 `assertAllowed` 抛异常兜底。
4. **跳过而非中断**：`Boss.assessCandidate` 先判冷却期，命中则本岗位 `return false`，不触发外发闸门异常、不中断整个任务。
5. **计次**：`Boss` 在 TEXT 动作 `finishAction` 后回调 `contactCallback` 写入；写入失败即停止后续外发（fail-closed）。
6. **页面**：方案页 BOSS 高级筛选区新增"HR 冷却期（天）"，前后端双重校验；检查项复用现有 `ScreeningEvidence` 渲染。

## 验收

见 `todo.md`，全部以可执行测试或实际接口验证为依据。
