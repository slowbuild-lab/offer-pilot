# 第一阶段验收记录

> 2026-10-20 清理说明：按用户要求，此次新增的 8 个测试源文件及生成的测试产物已删除。下方是删除前的历史验收记录，不代表当前仍保留这些可运行测试；项目原有测试保留。

日期：2026-10-20。范围：tasks/todo.md 任务 1–6。

## 构建与测试

执行 `./gradlew --offline --console=plain -PrebuildFrontend build` 成功。
使用本机 Java 21 与已安装 Node，浏览器测试使用临时 Chrome 上下文和拦截接口；所有数据库测试使用临时 SQLite。
共 111 项测试，0 失败、0 错误、0 跳过。

- BossRepeatChatConfigTest：2 项通过。
- ZhilianDeliveryLedgerTest：11 项通过。
- ZhilianPopupDelayConfigTest：37 项通过。
- ZhilianSafetyApiTest：3 项通过。
- BossRepeatChatTest：10 项通过。
- ZhilianControlledTaskTest：5 项通过。
- ZhilianJobServiceTest：11 项通过。
- ZhilianFrontendTest：3 项通过。
- ZhilianModernLayoutTest：21 项通过。
- ZhilianPageGuardTest：8 项通过。

## 关键证据

- 并发启动复现：修复前启动中 isRunning=false、worker 实际执行两次；修复后最多一次。
- 启动中停止复现：修复前 stop 丢失并误报成功；修复后 STOPPED。
- 岗位卡片选中后停止复现：修复前仍有一次 apply 点击；修复后为零。
- 结束清理期间停止复现：修复后内存与 SQLite 都保存 STOPPED。
- 单次及每日额度边界、并发占用、北京时间跨日和重启保留通过。
- 已确认和待核实记录去重、旧库重复行及仅采集岗位处理通过。
- 数据库故障时无额外投递；结束后未确认的记录可在列表核实。
- 连续三次未确认后第四岗位未点击；确认成功会重置连续异常计数。
- 可见验证码出现后暂停，已确认结果保留；隐藏、空和透明占位元素不会造成误暂停。
- 人工继续沿用原任务 ID 与配置快照，不因当前配置改变重置额度。
- 真实构建前端在隔离 Chrome 验证保存、回显、停止中禁止重启、登记确认与继续交互；没有 React 运行时错误。
- 对两个改动前端文件运行 ESLint：退出码 0，0 错误；原 page.tsx 仍有 7 条未使用变量/依赖数组警告，未禁用规则。
- git diff --check 通过。

## 数据保护

- 操作前备份：`../get_jobs-backups/2026-10-20-phase1-162704/`，含原数据库、工作区补丁与已修改源码归档。
- PRAGMA integrity_check 为 ok；重启前原 zhilian_data 全部行与备份逐行一致。
- 不以旧记录的 update_time 推算每日次数；首次任务后显示新统计起始时间。
- 未提交真实简历，未用用户账号执行测试投递。

## 本机运行检查

本机服务已启动（Java PID 27202），9527 与 6866 均监听 127.0.0.1。

- `/api/health`：HTTP 200，UP。
- `/api/playwright/status`：HTTP 200，initialized=true，hasBrowser=true；本次启动日志确认“浏览器自动化引擎初始化完成”。
- `/api/zhilian/status`：HTTP 200，isRunning=false，taskState=IDLE，isLoggedIn=true。
- `/api/zhilian/config`：HTTP 200，弹窗等待 5 秒，本次上限 20、每日上限 50。
- `/api/zhilian/attempts/unknown`：HTTP 200，total=0。
- `/zhilian`：HTTP 200；实际提供的 JavaScript 包含两个上限字段、人工继续按钮和待核实列表。
- 原 zhilian_data 与备份逐行一致，原配置所有旧字段逐行一致，数据库 integrity_check=ok。
- 生产 zhilian_attempts 记录数为 0，验证未发起真实投递。

## 后续阶段

第二阶段的筛选规则、预览与完整进度，以及第三阶段的完整断点恢复尚未实施；本次提供第一阶段结果供验收。
