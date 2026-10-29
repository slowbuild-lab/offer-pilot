# 首批任务

- [x] 规则失败测试、旧版本兼容、公司白名单、JD 包含/排除及证据。
- [x] API 严格校验与预览证据。
- [x] 临时 SQLite 筛选日志、唯一性/分页/北京日期测试。
- [x] BOSS 真实 JD 接入与隔离页面外发前拦截。
- [x] 方案页高级条件与预览依据。
- [x] 记录页候选筛选区域及加载/空态/失败/分页。
- [x] 全部回归、前端检查与构建、隔离浏览器。
- [x] 空闲确认、备份与本机更新、交付验收记录。

## 2026-10-28 复核记录

以下为较早一次工作区记录，最新本机状态与实际验收以 [首批交付验收](../../docs/boss-helper-first-batch-acceptance-20261028.md) 为准。后续已完成隔离浏览器交互、响应式布局及更新后真实服务只读检查；当前合并工作区完整后端测试为 73 项（包含另有的 HR 冷却期测试）。

- 后端 `./gradlew test`：54 项、0 失败、0 跳过（见 build/test-results/test）。
- 前端 `npx tsc --noEmit` 通过；`npx eslint` 仅剩 `front/scripts/*.js`、`tailwind.config.ts` 等既有文件的 `require()` 报错，本轮改动文件 0 问题。
- `next build` 通过，导出 15 个静态页面；产物复制到 `src/main/resources/dist`（103 个文件，RSC 预取别名 39 个与上一版一致）。
- `./gradlew build` 通过，产出 `build/libs/get_jobs-2.0.1.jar`。
- 本机服务已更新：旧实例（PID 2568，占 9527/6866）确认空闲后停止，数据库先备份到 `.local-backups/getjobs-before-first-batch-20261028-194732.db`（含 WAL）；新包在 9527 启动，`/api/health` 返回 UP。
- 只读接口校验：`/api/delivery/screenings` 的非法日期、起止倒置、非法结果值、页码越界均返回 400 与中文提示；跨站 Origin 返回 403；正常分页 200。
- 页面渲染校验：`/`、`/job-profile`、`/delivery-records` 均 200，`BOSS 高级筛选`、`BOSS 候选筛选记录` 文案已出现。
- 待人工确认：浏览器内实际保存/预览交互与手机宽度布局，需真实登录账号与点击操作，未在自动化中执行。
