# 第二批任务：HR 冷却期

- [x] 契约：`ContactPolicy` 天数范围 1–365、必须选 BOSS 平台、老方案缺字段视为未启用。
- [x] 候选事实：`JobCandidate.hrId/hrActiveRaw`、`Job.hrId`、`Boss` 采集处传入（三级兜底）。
- [x] 台账：`boss_contacts` 幂等记录、最近接触查询、独立于投递账本。
- [x] 预览：命中时产出 `BOSS_HR_COOLDOWN` 检查项、原因与上次接触证据。
- [x] 外发闸门：`assertAllowed` 二次校验，缺标识 fail-closed。
- [x] 跳过语义：预筛命中只跳过该岗位，不中断任务、不占额度、不产生账本预留。
- [x] 计次：TEXT 动作结束后写入；`RESERVED` 不写；写入失败停止后续外发。
- [x] 页面：方案页冷却期输入与前后端校验；检查项复用现有依据展示。
- [x] 回归、类型检查、构建、本机更新。

## 2026-10-28 验证记录

- 后端 `./gradlew test`：**73 项，0 失败 0 跳过**（首批 54 项 + 新增 19 项）。
- 新增用例：`ContactGuardTest`（8）、`ContactCooldownContractTest`（9）、`BossBrowserSafetyTest.recruiterCooldownSkipsTheSecondJobBeforeAnyExternalAction`（1，真实 Chrome + fixture，0.18s）、`JobProfileControllerTest` 冷却期入参（1）。
- 关键用例覆盖：同 HR 换岗位被拦、不同 HR 不误伤、窗口边界（第 29 天拦 / 第 30 天放）、缺标识 fail-closed、策略关闭零行为、智联不受影响、幂等记录、不占额度、控制器拒绝 0/366/字符串/小数/未知字段/纯智联方案。
- 前端 `npx tsc --noEmit` 通过；改动文件 `eslint` 无告警（既有 `scripts/*.js` 的 `require()` 报错非本轮引入，未动）。
- `next build` 通过（15 个静态页），产物复制到 `src/main/resources/dist`（103 文件）。
- `./gradlew build` 通过，产出 `build/libs/get_jobs-2.0.1.jar`（243M）。
- 本机服务已更新：旧实例空闲确认后停止，数据库备份到 `.local-backups/getjobs-before-cooldown-20261028-200511.db`；新包在 127.0.0.1:9527 启动，`/api/health` UP，新表 `boss_contacts` 已创建；`/job-profile` 页面已渲染"HR 冷却期（天）"。

## 待人工确认

- 真实账号下 `encryptUserId` 的覆盖率：若大面积缺失，按口径 A 会大量跳过；建议先跑一次只读预览看 `BOSS_HR_COOLDOWN` 的通过率，再决定是否放开。
- 浏览器内实际交互与手机宽度布局（自动化未覆盖）。
