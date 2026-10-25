# 多平台并行验收记录（2026-10-21）

本文只记录**本轮实际执行**的检查。此前的智联增强验收（111 项）见 [tasks/phase1-verification.md](../tasks/phase1-verification.md)，不能作为本次并行的证据。

## 验证方式

- 临时验收程序（JUnit，跑完即删除，未提交），配合本地隔离页面和临时 SQLite。
- 浏览器：本机 Chrome，headless，`-Dgetjobs.browser.no-sandbox=true -Dgetjobs.browser.disable-gpu=true`。
  这两个开关**仅用于本机受限环境验证**（该环境里 Chrome 无法初始化自己的 sandbox），默认不开启，生产默认仍保留 Chrome sandbox。
- 所有资料目录、数据库均为临时目录；`db/getjobs.db` 与 `browser-data/` 未被修改。接口冒烟测试用的是数据库副本。
- 没有登录真实账号、没有访问真实岗位列表、没有提交简历或发送聊天消息。

> 踩坑记录：重启持久化测试最初把临时库路径写在 `SpringApplicationBuilder.properties()` 里，优先级低于 `application.yaml`，实际连到了项目里的 `db/getjobs.db`。已改为命令行参数（最高优先级）并在启动后断言 URL 必须指向临时目录；受影响的测试记录已按原值回滚，最终运行前后 `db/getjobs.db` 的 md5 一致。

## 结果

| # | 验收项 | 结果 | 实际证据 |
|---|---|---|---|
| 1 | 两个平台的任务在时间上重叠 | 通过 | 智联在 Playwright 里等待 10 秒（模拟成功弹窗）期间，BOSS 完成 10 次点击 + evaluate，用时约 2.0 秒（最后一次运行 2074ms），远小于 10 秒 |
| 2 | 智联等待 10 秒时 BOSS 仍能完成本地模拟操作 | 通过 | 同上；两个任务分别在 `playwright-boss`、`playwright-zhilian` 两个线程上 |
| 3 | 停止一个平台，另一个继续 | 通过 | 停止 BOSS 后它在第 7 次迭代退出，智联仍跑完自己的 3 秒等待并返回页面状态 |
| 4 | 一个平台异常/验证码，另一个不受影响 | 通过 | 智联在调用中抛异常，BOSS 仍完成 15 步操作且页面可用 |
| 5 | 同平台连续启动只产生一个任务 | 通过 | 真实 `PlatformTaskCoordinator`：第二次启动返回 `PLATFORM_BUSY`，平台任务实例数=1；停止后任务未退出前仍拒绝，退出后才允许再次启动 |
| 6 | 全局并行上限有效 | 通过 | 上限 2：BOSS+智联运行中启动猎聘返回 `PARALLEL_LIMIT`，且猎聘任务未创建；释放一个槽位后可以启动 |
| 7 | 关闭一个平台浏览器不影响另一个 | 通过 | `boss.close()` 后 `browserReady=false`，智联仍能 `evaluate("1+1")=2` |
| 8 | 重启后配置、历史与去重保留 | 通过 | 同一个临时数据库启动两次 Spring：关键词/弹窗等待 7 秒/上限 3、5 保留；中断任务变 `PAUSED` 且原因含“中断”；待核实记录保留；已投递和待核实岗位均判为 `DUPLICATE` |
| 9 | 无 Playwright 跨线程访问错误 | 通过 | 全部会话操作都在所属线程；没有出现 `Object doesn't exist` / `TargetClosedError` / 管道断开 |
| 10 | 前后端完整构建 | 通过 | `gradlew compileJava`、`gradlew bootJar`、`next build`、`copy-dist.mjs` 全部成功 |

补充的接口冒烟（真实启动 jar，临时数据库副本）：

- `GET /api/platforms/status` → `maxParallel=2`，四个平台均 `IDLE`，四个浏览器均 `browserReady=false`（启动时没有打开任何浏览器）。
- `POST /api/zhilian/login` → 2 秒后只有智联的 `browserReady=true`，BOSS/猎聘/51job 仍为 false（按需启动 + 平台隔离）。
- 重复点打开智联登录 → Chrome 进程数 9 → 9，没有第二个浏览器。
- `POST /api/liepin/start`（未登录）→ 400 `not_logged_in`；`POST /api/boss/stop`（无任务）→ 400 `not_running`；`POST /api/platforms/nope/login` → 400；智联存在暂停任务时 `POST /api/zhilian/start` → 409 `paused`。

## 未覆盖

- 真实 BOSS／智联页面上的完整投递流程没有执行（按要求不发起真实投递）。本次验证的是会话隔离、任务调度和状态机，页面选择器兼容性仍以线上实际情况为准。
- 猎聘、前程无忧的并行没有验证；它们仍走原有任务入口，只是同样受并行上限约束，不能宣称已支持并行。
- 内存与实际风控表现没有做长时间压测；两个平台同时运行约等于两个 Chrome 实例。

## 清理

临时测试类、临时数据库、临时浏览器资料目录、本地页面服务均已在验证后删除；仓库只保留本次新增的验收记录。
