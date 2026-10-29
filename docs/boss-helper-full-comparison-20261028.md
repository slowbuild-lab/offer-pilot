# boss-helper 与求职作战台：全量功能差异与开发建议

日期：2026-10-28。交付类型：源码调研、能力盘点与开发决策文档。状态：待用户选择开发范围。

> 后续进展：用户已选择首批，BOSS 公司白名单、工作内容包含/排除和原因明细已实现并更新本机，见 [首批交付验收](boss-helper-first-batch-acceptance-20261028.md)。本文对照清单保留调研时的开发前快照，不能当作更新后当前功能状态。

参考项目固定版本：[Ocyss/boss-helper · 6da16a217e602f09aae3a5cee05f5a5caf0cb797](https://github.com/Ocyss/boss-helper/commit/6da16a217e602f09aae3a5cee05f5a5caf0cb797)。日志子模块固定版本：[Ocyss/devlog-ui · 2754bee64a7fc263b287926123acefc39d77107e](https://github.com/Ocyss/devlog-ui/tree/2754bee64a7fc263b287926123acefc39d77107e)。

作战台源码位置：仓库根目录/。HEAD 为 eabd997dab39d430b9d5e63e3a77a89854934d4f；本报告同时以当前尚未提交的前后端修改为依据，不能只看 HEAD。

## 阅读结论

1. **插件的优势集中在投递前的精细筛选、岗位卡片解释、提示词样本测试、多配置和模型管理。** 值得迁移产品能力，在现有作战台架构内独立实现。
2. **作战台在可信账本、未知结果保护、事务额度、两平台隔离、执行前预检、方案版本与材料确认方面已有更完整的基础。** 不应为了接入插件功能而削弱这些能力。
3. **有名称、有开关、有组件，不代表功能已经接通。** 插件的同公司/HR 去重键、好友状态、普通与 AI 话术互斥、模型高级参数、缓存、独立选项页、菜单入口等均存在需要辨别的实现边界。
4. **我们的旧平台设置不等于统一方案。** 经验/学历/公司规模等在 BOSS 旧页面存在，但统一方案映射会清除若干旧筛选，并关闭 AI 和重复沟通。需要新增统一规则的地方不能被旧 UI 掩盖。
5. **通知也需要区分代码与业务接入。** 作战台有企业微信格式 Bot 类和配置表单，但检索未找到业务调用；它既不是已接通的任务通知，也不是飞书。
6. **你的目标仍是每天少花时间。** 按现有 PRD（本地验收记录，不随仓库分发），阶段 B 是任务控制与飞书事件/配置/重试，阶段 C 是本机每日计划、常驻、日报。高级筛选与 AI 是增量，不能替代这条主线。

## 1. 如何确保清单闭合

本次不是根据 README 写功能清单，而是从“页面入口 → 配置字段 → 数据映射 → 处理器 → 真实调用 → 结果保存”逐项追踪，并反向检查作战台已有能力。

覆盖范围：

- 插件运行相关文件清单共 **84 个**：其中 83 个文本文件纳入读取和归类；另一个约 886KB 的压缩聊天库登记为协议依赖，未逐行审计。还检查了 README、LICENSE、package、隐私、构建发布等根文件。
- 插件全部 **29 项顶级业务配置、10 项外观配置、8 个模型配置分组**均有对应检查项；含嵌套字段与界面元数据共 **195 条结构路径**，未留未归属字段。195 不代表 195 个独立功能。
- 日志子模块的 **14 个非测试运行源码文件**纳入依赖入口检查。网络捕获、错误捕获、时间线等只有依赖导出而未接入的能力单独标记。
- 作战台清点 **164 个运行/配置源码文件、12 个页面入口、16 个控制器、117 处 Mapping 声明**。Mapping 声明数包含类级路径，不是独立 HTTP 接口总数。
- 形成 **12 类、172 个能力检查项**，涵盖差异、相同能力、我方优势、双方共同缺口、未接线与禁用项。
- 构建缓存、静态导出、副本依赖、锁文件、图片资源、作者 AI 指令目录和测试用例不当作独立产品功能；已说明的第三方边界不作运行效果承诺。

事实标记：

- “已有/接入”表示检查到源码入口及调用，不表示本轮真实账号验证成功。
- “部分/旧页”表示仅某路径、某平台或旧配置支持，不能推广到统一方案。
- “未接入”表示检查的公开入口与执行路径没有连通该能力。
- “配置残留/禁用/占位”不算现成可迁移功能。
- 开发建议和验收条件属于设计，不是实现结果。

## 2. 两边真实工作路径

### 插件

扩展 content script 注入 boss.js → 获取 BOSS 页面 Vue 对象与列表/分页/详情 → 挂载面板 → 用户配置并启动工作流 → 已沟通/重复/基础筛选 → 获取详情 → 活跃、地址、工作内容、地图和 AI 筛选 → 调用发布接口 → 获取聊天资料 → 自定义话术与 AI 话术处理器 → 更新页面状态与浏览器统计。

注意：自定义话术与 AI 话术在当前流程中是两个处理器；“AI 一定替代普通话术”不能仅凭帮助文案确认。依据：[P01](#p01)、[P02](#p02)、[P03](#p03)、[P04](#p04)。

### 作战台

保存求职方案新版本 → 平台只读采集样本 → 规则预览与材料确认 → 新任务绑定生效方案/配置快照 → 预检和平台并行占用 → worker 采集候选 → 规则、停止和紧急禁止检查 → 账本预留与动作登记 → 页面外发 → 分别确认文字/图片等动作 → 记录确认/待核实 → 首页与明细展示。

统一方案目前没有每日自动日轮；确认方案不等于开启每日自动计划。依据：[W01](#w01)、[W02](#w02)、[W03](#w03)、[W05](#w05)、[W08](#w08)。

### 结构上的迁移方式

建议把插件中的“规则处理器、带理由的候选展示、提示词测试台”转成现有 Java 服务与 React 页面中的能力。浏览器内部 Vue Hook、MQTT 发布与扩展消息桥不直接搬入作战台。它们与平台页面和插件权限绑定，无法替代当前的停止、额度和账本保护。

## 3. 全量能力对照

表中 P 为插件源码证据，W 为作战台源码证据，点击编号可到证据索引。P1/P2/P3 是新增借鉴项之间的建议优先级，原 PRD 的阶段 B/C 顺序仍单独成立。

### A. 产品定位、平台与运行入口（11 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| A01 | 产品形态 | 在 BOSS 页面注入面板 | 独立本机作战台，前端与 Java 服务分离 | 保留现有结构；迁移能力 | [P16](#p16) / [W08](#w08) |
| A02 | 招聘平台范围 | 只接 BOSS | 四个平台入口；统一方案/额度重点接 BOSS、智联 | 保留；四入口不等于四平台同等验证 | [P16](#p16) / [W01](#w01) / [W15](#w15) |
| A03 | 搜索页/推荐页 | 兼容页面内搜索与推荐列表 | 通过 worker 搜索关键词和城市 | 可补推荐来源标记 | [P01](#p01) / [W04](#w04) |
| A04 | 原生筛选复用 | 移动 BOSS 原生筛选 DOM 到面板 | 平台配置表单与选项字典 | 不迁移 DOM；统一规则语义 | [P13](#p13) / [W10](#w10) |
| A05 | 登录会话 | 复用用户当前浏览器登录 | 按平台持久浏览器资料与登录入口 | 保留隔离 | [P16](#p16) / [W08](#w08) |
| A06 | 浏览器兼容 | WXT 配置含 Chrome/Edge/Firefox | 本机 Java、Node、Chrome/Playwright 运行链 | 部署方式不同，不直接复制 | [P16](#p16) / [W08](#w08) |
| A07 | 页面关闭与任务 | 执行上下文依赖页面 | 服务端执行；关闭前端不等于停止任务 | 明确显示任务仍运行 | [P04](#p04) / [W08](#w08) |
| A08 | 多招聘账号 | README 已废弃，改多配置 | 每个平台资料目录，不是同平台多账号管理 | 两边均不能声称完整多账号 | [P19](#p19) / [W08](#w08) |
| A09 | 移动端 | README 勾选适配，但主容器固定宽度 | 移动导航与响应式页面已有 | 保持现有；插件适配需实测 | [P13](#p13) / [W16](#w16) |
| A10 | 明暗主题 | 配置关闭 colorMode，说明不维护暗黑 | 已有主题切换 | 保留作战台优势 | [P13](#p13) / [P16](#p16) / [W16](#w16) |
| A11 | 打包与发布 | GitHub Actions 用 Bun 构建扩展 ZIP/发布 latest；商店提交脚本另存 | Gradle/Next 本机启动包与静态资源，不是扩展商店发布 | 工程能力不同，不能直接复用发布流水线 | [P20](#p20) / [W19](#w19) |

### B. 搜索条件与岗位筛选（25 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| B01 | 岗位搜索关键词 | 复用原生搜索；另有标题包含词 | 统一方案最多 10 个；旧平台配置也有 | 已有；区分搜索与筛选 | [P03](#p03) / [P13](#p13) / [W01](#w01) |
| B02 | 目标城市 | 复用 BOSS 原生条件 | 统一方案单城市；旧 BOSS 支持城市列表 | 统一方案多城市属新增 | [P13](#p13) / [W01](#w01) / [W10](#w10) |
| B03 | 岗位标题包含 | 包含模式模糊匹配 | 统一方案关键词匹配标题 | 已有；核对大小写口径 | [P03](#p03) / [W01](#w01) |
| B04 | 岗位标题排除 | 排除模式模糊匹配 | 统一方案排除项与旧黑名单 | 已有；不重做 | [P03](#p03) / [W01](#w01) / [W10](#w10) |
| B05 | 公司名称排除 | 公司词条排除 | 统一方案及 BOSS 黑名单 | 已有 | [P03](#p03) / [W01](#w01) / [W10](#w10) |
| B06 | 公司白名单 | 包含模式只投指定公司词条 | 统一方案只有公司排除项 | P1：增加可选白名单 | [P03](#p03) / [W01](#w01) |
| B07 | 工作内容排除 | 读取 JD 后检查排除词 | 采集 JD，但统一规则不检查 | P1：接入统一条件 | [P03](#p03) / [W01](#w01) / [W04](#w04) |
| B08 | 工作内容必须包含 | JD 包含模式 | 统一方案没有 | P1：与排除规则同时设计 | [P03](#p03) / [W01](#w01) |
| B09 | 否定语境与词义 | 正则避开部分不/无及系统/软件/工具/服务语境 | 无 JD 专用语境规则 | P1：显式例外与命中原文；不能照搬正则 | [P03](#p03) / [W01](#w01) |
| B10 | HR 职位筛选 | 精确包含/排除职位名称 | 旧 BOSS HR 职位黑名单按包含词 | 补强：区分职位类别和具体 HR | [P03](#p03) / [W04](#w04) / [W10](#w10) |
| B11 | 地区/地址文字 | 工作地址包含词 | 有地址数据，统一方案无地址字段 | P1：区县/商圈优先或排除 | [P03](#p03) / [W01](#w01) / [W04](#w04) |
| B12 | 公司人数范围 | 数值区间筛选 | 旧 BOSS 公司规模选项；统一方案清空旧过滤 | 补强统一规则，不能把旧开关算已接入 | [P03](#p03) / [W03](#w03) / [W10](#w10) |
| B13 | 薪资上下限 | K/月薪等范围 | 统一方案最低固定月薪 | 按需要补最高值与区间偏好 | [P03](#p03) / [W01](#w01) |
| B14 | 薪资严格/宽松 | 区间包含或相交 | 固定月薪下界及硬条件/偏好 | 语义不同；写清示例再开发 | [P03](#p03) / [W01](#w01) |
| B15 | 日薪/时薪 | 元/时、元/天单独阈值 | 统一方案对非固定月薪谨慎处理 | P2：仅需要兼职时再加 | [P03](#p03) / [W01](#w01) |
| B16 | 薪资换算 | 按 21.75 天、8 小时同步配置 | 统一方案不据此推断实际月薪 | 不自动视为等价收入 | [P13](#p13) / [W01](#w01) |
| B17 | 面议/未知单位 | 未命中识别单位可能直接通过 | 硬月薪规则无法确认则跳过 | 保留作战台处理，补显式未知策略 | [P03](#p03) / [W01](#w01) |
| B18 | 经验与学历 | 原生筛选提供 | 旧页已有；统一方案未声明且清空旧条件 | P1：需显式纳入统一方案 | [P13](#p13) / [W03](#w03) / [W10](#w10) |
| B19 | 行业/融资/工作类型 | 依赖 BOSS 原生筛选 | 旧 BOSS 已有；统一方案不继承 | 按需求纳入，避免双配置冲突 | [P13](#p13) / [W03](#w03) / [W10](#w10) |
| B20 | HR 活跃过滤 | 描述月/年及时间差分支 | BOSS 开关主要检查描述含年 | P1：改成有依据的阈值策略 | [P03](#p03) / [W04](#w04) |
| B21 | 自定义活跃天数 | 现有处理器采用固定分支，不是任意天数 UI | 统一方案没有阈值字段 | 属于新设计，非现成功能迁移 | [P03](#p03) / [W01](#w01) |
| B22 | 猎头过滤 | goldHunter 字段过滤投递候选 | 分析页猎头筛选不等于 worker 已排除 | P2：明确来源并接执行条件 | [P03](#p03) / [W13](#w13) |
| B23 | 金牌面试官 | 头像装饰 URL 命中过滤 | 没有此规则 | 不建议以装饰判断招聘真实性 | [P02](#p02) / [W01](#w01) |
| B24 | 每条筛选开关 | 各规则独立 enable/include | 统一方案按字段与薪资硬条件 | 补强：基础/高级分层，避免开关泛滥 | [P05](#p05) / [P06](#p06) / [W01](#w01) |
| B25 | 规则预览与执行一致 | 有真实投递流水线，无全规则确认快照 | 已有版本预览、24 小时有效及执行前检查 | 保留统一方案优势 | [P02](#p02) / [W02](#w02) / [W03](#w03) |

### C. 去重、黑名单与接触保护（10 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| C01 | 已沟通过岗位 | raw jobitem.contact 在流程首部始终过滤 | 继续沟通按钮判断 + 岗位账本 | 都有；语义需分清 | [P02](#p02) / [W04](#w04) / [W05](#w05) |
| C02 | 已聊 HR 好友开关 | isFriend 初值 false；详情映射未看到回填 | 没有等价的好友关系规则 | 插件字段不能当可靠已接入能力 | [P01](#p01) / [P03](#p03) / [W04](#w04) |
| C03 | 同 HR 去重 | 处理器却使用岗位 key | 账本主要按岗位 ID | P1：按可靠 HR 标识重新实现 | [P01](#p01) / [P03](#p03) / [W05](#w05) |
| C04 | 同公司去重 | 处理器同样使用岗位 key | 公司排除规则不等于近期去重 | P1：可靠主体标识、可选上限 | [P01](#p01) / [P03](#p03) / [W05](#w05) |
| C05 | 去重过期/冷却 | 时间戳存储、保存时清理 | 岗位账本保护无此冷却配置 | 新增 HR 冷却；不释放未知动作 | [P03](#p03) / [P04](#p04) / [W05](#w05) |
| C06 | 写入去重记录时机 | 在筛选处理器 after 内写入 | 外发前预留并按动作记录 | 保留账本事务语义 | [P03](#p03) / [P04](#p04) / [W05](#w05) |
| C07 | 统一黑名单管理 | README 待办；已有排除词不是独立管理中心 | BOSS 公司/岗位/HR 职位黑名单 CRUD | 保留并统一入口 | [P19](#p19) / [W10](#w10) |
| C08 | 运行中的紧急禁止 | 未看到等价的全局外发前禁止表 | 已有公司/具体岗位禁止及外发前核验 | 保留作战台优势 | [P02](#p02) / [W02](#w02) / [W03](#w03) |
| C09 | 跨平台公司/HR 去重 | 仅 BOSS，不涉及跨平台 | 尚无可靠跨平台主体映射 | 不能凭名称相近自动合并 | [P16](#p16) / [W01](#w01) / [W05](#w05) |
| C10 | 结果未知防重复 | 一般错误落岗位 error，可再次处理 | UNKNOWN 保留占额和去重、可人工核实 | 保留；不可用缓存替代账本 | [P04](#p04) / [W05](#w05) / [W17](#w17) |

### D. 执行、任务控制与额度（20 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| D01 | 启动投递 | 页面内工作流执行 | 服务端任务 + 各平台入口 | 已有；沿用当前调度结构 | [P04](#p04) / [W08](#w08) |
| D02 | 暂停/继续 | 页面 status=stop，再 start | 智联暂停继续；BOSS 停止后新任务，非同一恢复接口 | 不要把四平台恢复能力混为一谈 | [P04](#p04) / [W15](#w15) / [W17](#w17) |
| D03 | 重置筛选结果 | 把非成功卡片改回等待 | 没有同等入口；已发动作受账本保护 | 可重算筛选，不重置投递账本 | [P04](#p04) / [W05](#w05) |
| D04 | 开始前延迟 | 独立 delayDeliveryStarts | 没有统一方案对应字段 | 低优先级，可统一任务节奏 | [P05](#p05) / [W04](#w04) |
| D05 | 岗位间隔 | 独立 delayDeliveryInterval | BOSS waitTime 与岗位间停顿 | 已有部分，统一语义即可 | [P05](#p05) / [W04](#w04) |
| D06 | 翻页间隔 | 独立 delayDeliveryPageNext | worker 内翻页/等待 | 可补配置，默认保持保守 | [P01](#p01) / [P05](#p05) / [W04](#w04) |
| D07 | 发消息前延迟 | delayMessageSending，可编辑 pending 文本 | 无等价的逐条编辑窗口 | P2：人工模式可选 | [P01](#p01) / [P08](#p08) / [W04](#w04) |
| D08 | 自动翻页/停止条件 | 页码/滚动、列表首项变化等 | 各 worker 的列表和分页处理 | 都有；不照搬页面内部函数 | [P01](#p01) / [W04](#w04) / [W15](#w15) |
| D09 | 执行前预检 | 配置构建时可报错，无同等作战台预检 | 登录、配置、额度、并行、浏览器检查 | 保留作战台优势 | [P04](#p04) / [W09](#w09) |
| D10 | 本地数量上限 | 以成功完成工作流数量为主 | BOSS/智联按尝试、预留与未知占额 | 不得把成功数当实际额度 | [P04](#p04) / [P12](#p12) / [W05](#w05) / [W06](#w06) |
| D11 | 额度修改生效时机 | 表单改值保存，无同等延迟上调策略 | 下调立即，上调北京时间次日 | 保留作战台优势 | [P05](#p05) / [W06](#w06) |
| D12 | 平台并行隔离 | 单 BOSS 页面流程 | 平台执行器/资料目录/并行票据 | 保留；不能共享插件上下文 | [P16](#p16) / [W08](#w08) |
| D13 | 全部停止/强制停止 | 页面暂停，没有跨平台控制 | 已有 stop-all/force-stop | 保留；范围按实际平台状态 | [P04](#p04) / [W08](#w08) / [W15](#w15) |
| D14 | 错误分类与停止 | 定义限额/频率异常，但通用 per-job catch 可能继续下个岗位 | 任务状态与异常提示、智联暂停等 | 限额/频控必须有明确全局暂停验收 | [P04](#p04) / [P17](#p17) / [W15](#w15) / [W17](#w17) |
| D15 | 请求重试 | 发布请求含网络异常递归重试 | 未知外发按账本处理，不自动重投 | 不能直接搬重试写请求逻辑 | [P17](#p17) / [W05](#w05) |
| D16 | 任务历史/配置快照 | 当前页面 Map 和累计统计 | 任务表、方案版本、配置快照 | 保留作战台优势 | [P04](#p04) / [W05](#w05) / [W07](#w07) |
| D17 | 重启恢复 | 页面状态不是持久任务账本 | 中断任务转暂停、未确认动作转未知 | 保留作战台优势 | [P04](#p04) / [W05](#w05) |
| D18 | 验证码/安全验证 | 未见自动处理完整流程 | 有异常识别和人工处理路径 | 人工处理；不规划自动解验证码 | [P17](#p17) / [W04](#w04) / [W15](#w15) |
| D19 | 每日自动计划 | 未见成套每日调度 | 尚未接入；现有 @Scheduled 用于状态/登录检查 | 原 PRD 阶段 C | [P16](#p16) / [W03](#w03) / [W08](#w08) |
| D20 | 本机后台常驻/补跑 | 扩展后台桥不等于无人值守调度 | 服务能独立运行；无完整常驻与每日补跑方案 | 阶段 C 需补睡眠/重启/漏跑规则 | [P16](#p16) / [W08](#w08) |

### E. 招呼语、简历与消息发送（12 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| E01 | 固定招呼语 | 已有 | 已有；统一方案本人确认文本 | 保留 | [P07](#p07) / [W01](#w01) / [W04](#w04) |
| E02 | 模板变量 | 按对象路径渲染双花括号 | 统一方案未接变量 | P2：公司/岗位变量与样本预览 | [P07](#p07) / [W01](#w01) |
| E03 | 多段文字/图片顺序 | 可添加/删除/插入消息项 | 固定文字 + 可选单张图片简历 | P2：按需求扩展，动作分别记录 | [P07](#p07) / [W04](#w04) / [W14](#w14) |
| E04 | 图片素材保存 | IndexedDB、内容 MD5 键 | 本机图片文件，格式/大小检查及元数据 | 保留本机素材；可增加素材库 | [P07](#p07) / [P17](#p17) / [W14](#w14) |
| E05 | 发送前人工编辑 | pendingMessages 绑定输入框并延迟发送 | 统一方案确认文本，无逐条编辑倒计时 | 适合人工辅助模式，非无人值守必需 | [P01](#p01) / [P08](#p08) / [W01](#w01) |
| E06 | AI 招呼语 | 独立模型与 prompt | 旧 BOSS 有 AI；统一方案关闭 | P2：作为独立可选能力 | [P03](#p03) / [P08](#p08) / [W03](#w03) / [W11](#w11) |
| E07 | 普通/AI 招呼语互斥 | 帮助文案称替代；流程两处理器可同时启用 | 旧逻辑选消息；统一方案固定话术 | 若开发须明确定义只发一种 | [P02](#p02) / [P03](#p03) / [W04](#w04) |
| E08 | 重复沟通/跟进 | 流程首部过滤已沟通，非跟进工具 | BOSS 旧配置可选再次沟通及跟进消息；统一方案关闭 | 保留默认关闭，不误当自动回复 | [P02](#p02) / [W03](#w03) / [W04](#w04) |
| E09 | 在线简历确认 | BOSS 沟通流程，不等于 PDF 附件管理 | 智联确认在线简历；BOSS 图片简历 | 两种材料分开标识 | [P02](#p02) / [W01](#w01) / [W14](#w14) |
| E10 | 实际发送技术 | 内部发布接口 + MQTT 聊天协议 | Playwright 页面操作 | 复用现有平台适配与账本 | [P17](#p17) / [W04](#w04) |
| E11 | 消息成功依据 | publish QoS 与全流程状态，不等于招聘端已读 | 文字/图片 UI 证据与独立 UNKNOWN | 保留当前证据边界；都不能称已读 | [P01](#p01) / [P17](#p17) / [W04](#w04) / [W05](#w05) |
| E12 | 更多消息类型 | 协议构造器有贴纸/已读等，产品发送分支只接文字/图片 | 未接统一音频/视频/PDF 任意发送 | 协议声明不算产品功能 | [P17](#p17) / [W14](#w14) |

### F. AI 模型、提示词与调试（20 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| F01 | 模型配置库 | 新建、编辑、复制、删除多条配置 | 环境配置单组 BASE_URL/API_KEY/MODEL | P2：多模型库 | [P10](#p10) / [W11](#w11) |
| F02 | 按任务选择模型 | 筛选/招呼语分别绑定 model key | 共享全局服务配置 | P2：分任务模型和预算 | [P08](#p08) / [P10](#p10) / [W11](#w11) |
| F03 | AI 评分过滤 | 加分减扣分与阈值 | 没有统一投递前 AI 评分 | P2：先建议后自动阈值 | [P03](#p03) / [W03](#w03) / [W11](#w11) |
| F04 | 评分理由 | positive/negative 理由与分值 | 旧话术可包含筛选判断，无独立评分结果模型 | 新增结构化证据，不称面试概率 | [P03](#p03) / [P09](#p09) / [W11](#w11) |
| F05 | 多角色提示词 | system/user/assistant 消息编辑 | 技能介绍 + 单段格式化 prompt | P2：支持模板版本化 | [P09](#p09) / [W11](#w11) |
| F06 | 变量说明与默认示例 | 变量表链接、恢复示例 | 输入提示文案，无同等变量编辑器 | P2：可插入变量、即时校验 | [P09](#p09) / [W11](#w11) |
| F07 | 模型连接测试 | 输入测试问题、流式查看结果 | 后端 /api/ai/chat 存在；AI 页无同等测试台 | P1/P2：增加只生成测试入口 | [P10](#p10) / [W11](#w11) |
| F08 | 岗位样本测试台 | 采集 1/4/10 样本，查看多次输出 | 规则只读预览，不是 AI 提示词样本测试 | P2：与投递完全隔离的测试台 | [P09](#p09) / [W02](#w02) / [W03](#w03) |
| F09 | 测试批次/停止 | 分批四个请求，停止影响后续处理 | 无对应样本测试批次 | 明确软停止与取消在途请求区别 | [P09](#p09) / [W11](#w11) |
| F10 | 岗位 AI 过程面板 | 按岗位切换 JD/评分/话术及流式输出 | 进度/服务日志，无对应 AI 侧栏 | P2：简化为依据、结果、耗时 | [P08](#p08) / [W07](#w07) / [W11](#w11) |
| F11 | 流式模型结果 | 正式 chatModel 使用 agent.stream | AiService 非流式 | P2：仅需要查看过程时加入 | [P08](#p08) / [W11](#w11) |
| F12 | ChatCompletions/Responses | 测试模型支持切换；正式 createAgent 固定 provider.chat | 按模型与错误兼容 Responses | 配置必须贯穿测试和正式运行 | [P08](#p08) / [P10](#p10) / [W11](#w11) |
| F13 | 高级模型参数 | 表单含采样、JSON、stream、工具、额外头/体；正式路径未全部透传 | 少量固定参数，无同等高级 UI | 按实际支持字段设计，不能照抄全部开关 | [P08](#p08) / [P10](#p10) / [W11](#w11) |
| F14 | 超时语义 | 表单说明、通用请求与 agent 参数口径不同 | 服务请求 60 秒连接/请求设置 | 统一单位与可见错误 | [P08](#p08) / [P10](#p10) / [W11](#w11) |
| F15 | AI 失败时行为 | 可将当前岗位标为错误；无统一降级合同 | 旧话术失败可回退固定话术；统一方案无 AI | 区分建议失败与硬筛选失败 | [P04](#p04) / [W04](#w04) / [W11](#w11) |
| F16 | 自动回复招聘聊天 | UI disabled，README 待办 | 未接入自动回复 | 两边都不能算已有 | [P19](#p19) / [P13](#p13) / [W11](#w11) |
| F17 | 内容记录/训练 | record 字段存在，入口注释 | 账本与岗位数据不是训练导出功能 | 不列入可直接迁移 | [P05](#p05) / [P13](#p13) / [W05](#w05) |
| F18 | AI 成本上限 | 未见完整每日调用预算 | 没有统一评分预算 | 评分接入时增加预算与用量 | [P08](#p08) / [W11](#w11) |
| F19 | 模型协议范围 | 多条 OpenAI 兼容配置，不等于多协议原生适配 | OpenAI 兼容与 Responses 兼容 | 按接口能力区分供应商名称 | [P10](#p10) / [W11](#w11) |
| F20 | 模型导入导出 | 导出整个模型对象，可能含 API Key | 没有等价模型配置 JSON 导出 | 只导出脱敏配置或显式选择密钥 | [P10](#p10) / [W11](#w11) |

### G. 地址与通勤（9 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| G01 | 起点地址转坐标 | 高德地理编码 | 未接入 | P2：先地区、后地图 | [P11](#p11) / [W01](#w01) |
| G02 | 直线距离 | 有计算与阈值 | 未接入 | P2：偏好展示 | [P03](#p03) / [P11](#p11) / [W01](#w01) |
| G03 | 驾车距离 | 有计算与阈值 | 未接入 | P2：复用地图服务能力 | [P03](#p03) / [P11](#p11) / [W01](#w01) |
| G04 | 驾车时间 | 有计算与阈值 | 未接入 | 标明估算时间和交通方式 | [P03](#p03) / [P11](#p11) / [W01](#w01) |
| G05 | 步行距离 | 有计算与阈值 | 未接入 | 按需要加入 | [P03](#p03) / [P11](#p11) / [W01](#w01) |
| G06 | 步行时间 | 有计算与阈值 | 未接入 | 按需要加入 | [P03](#p03) / [P11](#p11) / [W01](#w01) |
| G07 | 公交/地铁通勤 | 检查到的地图路径未提供 | 未接入 | 新增开发，不能称插件现成支持 | [P11](#p11) / [W01](#w01) |
| G08 | 定位失败与地址来源 | 无地址、定位/路由异常可跳过或报错 | 地址已采集，未做地图判断 | 用实际工作地点；未知不满足硬条件 | [P03](#p03) / [P11](#p11) / [W04](#w04) |
| G09 | 地图变量与 Key | 有 Key/起点配置；帮助示例与 state 数据路径不一致 | 环境页无地图项 | 明确数据合同、缓存与密钥位置 | [P03](#p03) / [P07](#p07) / [P11](#p11) / [W11](#w11) |

### H. 方案与配置管理（10 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| H01 | 创建命名预设 | 已有 createPreset | 有版本保存，无命名方案库入口 | P2：创建模板 | [P05](#p05) / [W02](#w02) |
| H02 | 预设切换 | 选择预设后加载配置 | 只展示最新草稿与当前生效版本 | P2：切换先形成草稿 | [P05](#p05) / [W02](#w02) |
| H03 | 复制方案/版本差异 | 模型可复制；方案创建基于当前值，无完整版本差异台 | 已有不可变版本，缺浏览/复制比较 UI | 补强现有版本体系 | [P05](#p05) / [P10](#p10) / [W02](#w02) |
| H04 | 保存/重载 | 保存配置、重载本地值 | 保存新草稿、预览、确认 | 不绕过确认机制 | [P05](#p05) / [W02](#w02) |
| H05 | 推荐配置 | 应用部分默认项，不自动保存 | 无同等推荐项应用按钮 | P2：保守模板与差异预览 | [P05](#p05) / [W01](#w01) |
| H06 | 清空配置 | 高级按钮清空当前编辑值，需保存 | 无对应统一清空；可编辑新草稿 | 低优先级，避免误操作 | [P05](#p05) / [W02](#w02) |
| H07 | 配置 JSON 分享 | 导入/导出，导入检查 JSON 对象/数组为主 | 岗位 CSV 与配置分享不同；统一方案无此入口 | P2：严格字段校验、脱敏 | [P05](#p05) / [P10](#p10) / [W02](#w02) / [W13](#w13) |
| H08 | 历史配置迁移 | 按 version 迁移旧字段/提示词/延迟 | 数据库增量建表与方案版本 | 分别保留；导入时显式迁移 | [P05](#p05) / [W02](#w02) / [W05](#w05) |
| H09 | 运行中配置修改 | 部分表单在 running 时禁用 | 任务绑定快照，新草稿不改当前任务 | 保留快照，明确下次生效 | [P13](#p13) / [W02](#w02) / [W05](#w05) |
| H10 | 素材绑定与确认 | 图片可保存，无同等版本确认门禁 | 图片元数据绑定，变化后重新预览 | 保留作战台优势 | [P07](#p07) / [W02](#w02) / [W03](#w03) / [W14](#w14) |

### I. 候选展示、统计与数据记录（16 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| I01 | 候选岗位卡片 | 技能、标签、福利、地址、HR 活跃与状态 | 分析页表格/详情与方案预览，首页无同等卡片带 | P1：简化候选详情面板 | [P01](#p01) / [P13](#p13) / [W07](#w07) / [W13](#w13) |
| I02 | JD 查看 | 点击卡片读取/展开 | BOSS 分析页有文字详情 | 已有部分；合并入口 | [P01](#p01) / [P13](#p13) / [W13](#w13) |
| I03 | 当前处理进度与跟随 | 页面处理 current/total、卡片滚动跟随 | 平台任务状态，缺岗位步骤级首页跟随 | P1：当前岗位与阶段 | [P01](#p01) / [P04](#p04) / [W07](#w07) |
| I04 | 按筛选步骤查看岗位 | 卡片按 success/error/步骤筛选 | 记录按日期/平台/结果；非完整筛选原因列表 | P1：补筛选原因查询 | [P13](#p13) / [W06](#w06) |
| I05 | 今日扫描岗位数 | execute 次数累计，不保证唯一岗位 | 今日账本尝试数据，不是全量扫描统计 | 新增指标需唯一岗位口径 | [P04](#p04) / [P12](#p12) / [W06](#w06) |
| I06 | 过滤/重复/不活跃比例 | 有比例；总数减成功可能含错误 | 没有同口径完整原因漏斗 | P1：错误与规则排除分开 | [P12](#p12) / [W07](#w07) |
| I07 | 近 3/7/30 天/历史累计 | 浏览器按日累计成功数 | 记录日期筛选，首页今日，暂无同等快捷周期卡 | P2：用数据库聚合补快捷视图 | [P12](#p12) / [W06](#w06) |
| I08 | 额度进度条 | success/deliveryLimit | 预留、尝试、有效额度与待核实 | 保留当前口径，可补进度呈现 | [P12](#p12) / [W06](#w06) |
| I09 | 步骤计数 | tasks 字典累计步骤状态；不等于完整原因图表 UI | 缺统一筛选步骤事实表 | P1：先结构化记录再统计 | [P04](#p04) / [P12](#p12) / [W06](#w06) |
| I10 | 多维岗位筛选 | 原生筛选与卡片状态 | 平台分析有地区/经验/学历/薪资/关键词等 | 保留现有分析能力 | [P13](#p13) / [W13](#w13) |
| I11 | 分布图表 | 统计页主要数字与比例 | 平台分析含状态、公司、经验、学历、薪资等图表 | 作战台已有更广能力 | [P12](#p12) / [W13](#w13) |
| I12 | 岗位 CSV 导出 | 配置 JSON 不等于岗位 CSV；未见等价岗位导出入口 | 四个平台分析页有 CSV 逻辑 | 已有；区分岗位库与账本 | [P05](#p05) / [W13](#w13) |
| I13 | 可信记录查询 | 浏览器累计与页面内 Map 为主 | SQLite 账本，日期/状态过滤与真实分页 | 保留作战台优势 | [P04](#p04) / [P12](#p12) / [W05](#w05) / [W06](#w06) |
| I14 | 人工核实结果 | 无同等 UNKNOWN 处理台 | BOSS/智联待核实登记 | 保留作战台优势 | [P04](#p04) / [W05](#w05) / [W17](#w17) |
| I15 | 时间/跨日口径 | 统计 date 在 composable 创建时取本地日期 | 额度按 Asia/Shanghai；记录按北京日期 | 长期常驻时需专门验证跨日 | [P12](#p12) / [W06](#w06) |
| I16 | 读取失败与离线 | 有 toast，但无相同聚合状态合同 | 首页不可用/null、更新时间与离线控制 | 保留当前显示边界 | [P13](#p13) / [W07](#w07) |

### J. 通知、日志与诊断（14 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| J01 | 浏览器桌面通知 | 后台 notifications，页面不可见时通知结束 | 没有同等桌面通知；企业微信 Bot 仅有代码，未找到业务调用 | 可作为飞书之外的备选 | [P14](#p14) / [P16](#p16) / [W12](#w12) |
| J02 | 页内通知 | toast、Alert 与提示 | 状态提示、错误、确认弹窗 | 已有；统一文案 | [P13](#p13) / [W07](#w07) |
| J03 | 飞书通知 | 作者飞书反馈表不是通知集成 | 企业微信格式 Bot 未接业务；飞书也未实现 | 原 PRD 阶段 B 需真正实现 | [P15](#p15) / [W12](#w12) |
| J04 | 事件队列与重试 | 无成套业务事件持久队列 | Bot 有直接发送方法，但未接业务事件和持久重试 | 阶段 B 新增 | [P14](#p14) / [W12](#w12) |
| J05 | 每日飞书摘要 | 未见完整方案 | 未接每日计划/日报 | 阶段 C，不能当已具备 | [P12](#p12) / [W12](#w12) |
| J06 | 页面日志查看 | Logs 页接 DevLoggerUI | SSE/任务进度与服务日志，无同等内嵌筛选控制台 | P1/P2：用户版运行记录 | [P14](#p14) / [W18](#w18) |
| J07 | 日志过滤 | 级别、文本、源码文件筛选 | 记录业务结果筛选，不是日志内容搜索 | 可补任务/平台/原因检索 | [P14](#p14) / [W06](#w06) / [W18](#w18) |
| J08 | 日志复制/清空 | 依赖 UI 支持 TXT/JSON 复制、清空 | 没有对应前端诊断导出按钮 | P1/P2：脱敏诊断包 | [P14](#p14) / [W18](#w18) |
| J09 | 日志持久/容量 | session 持久 500、内存最多 1000 配置 | 服务日志与业务 SQLite 生命周期不同 | 定义保留期与隐私范围 | [P14](#p14) / [W05](#w05) |
| J10 | 上下文/耗时分组 | withContext、span | 任务 ID/账本时间已有，无同等分组查看器 | 复用任务 ID 关联，不另造账本 | [P14](#p14) / [W05](#w05) |
| J11 | 资源占用 | 日志依赖可看 Chrome JS 堆，不是整机 CPU/RAM | 无同等前端资源面板 | 低优先级，不影响投递主线 | [P14](#p14) / [W08](#w08) |
| J12 | 网络/全局错误/时间线 | 依赖导出相关 API；插件未看到安装调用或完整入口 | 有健康/浏览器诊断，不是相同监测器 | 标为未启用，不能按依赖 README 承诺 | [P14](#p14) / [W08](#w08) |
| J13 | 菜单日志/存储弹窗 | AppMenu 项 disabled，组件内容近占位 | 无等价全局存储弹窗 | 不是成熟功能，无需搬 | [P13](#p13) / [W16](#w16) |
| J14 | 服务健康和浏览器诊断 | 无独立本机服务诊断 API | 健康、浏览器状态、指纹/诊断等接口 | 保留；诊断接口不代表投递验证 | [P16](#p16) / [W08](#w08) / [W15](#w15) |

### K. 外观、帮助、更新与非业务功能（14 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| K01 | 交互帮助模式 | 高亮 data-help 元素并浮动解释 | 字段说明已有，无全局引导模式 | P2：少量首次引导即可 | [P13](#p13) / [W16](#w16) |
| K02 | 熟练度分层 | 新手/初学者/中级/高级分层 | 无等价等级切换 | 借鉴折叠高级项，避免暴露过多设置 | [P05](#p05) / [P13](#p13) / [W16](#w16) |
| K03 | 首次使用协议 | AppMenu 有代码，但默认挂载被注释 | 无同等协议弹窗 | 标未达入口，非已上线能力 | [P01](#p01) / [P13](#p13) / [W16](#w16) |
| K04 | 隐藏原站头部/去广告 | hideHeader 与 removeAd | 独立作战台无需处理 BOSS 原站布局 | 不迁移站点改造代码 | [P01](#p01) / [P13](#p13) / [W16](#w16) |
| K05 | 图标/动态标题 | 可改成计算器图标/标题并显示数量 | 固定产品标识，无伪装功能 | 只考虑任务进度标题，伪装低价值 | [P13](#p13) / [W16](#w16) |
| K06 | 模糊岗位卡片 | 光圈式模糊交互 | 没有对应功能 | 低优先级，隐私需求另设计 | [P13](#p13) / [W16](#w16) |
| K07 | 列表下沉/内容偏移 | 控制原站布局 | 独立页面已有布局组件 | 不直接迁移 | [P13](#p13) / [W16](#w16) |
| K08 | AI 面板位置/宽度/默认打开 | leftChat/chatBoxWidth/defaultShowChatBox | 没有 AI 过程侧栏 | 伴随 AI 面板再做 | [P13](#p13) / [W16](#w16) |
| K09 | 更换背景开关 | changeBackground 字段存在，未见使用入口 | 主题样式已有 | 配置残留，不算已实现 | [P05](#p05) / [P13](#p13) / [W16](#w16) |
| K10 | 提示关闭后记忆 | Alert 按 ID 存储关闭状态 | 状态和错误提示主要实时展示 | 仅普通帮助可记忆，关键异常不可隐藏 | [P13](#p13) / [W07](#w07) |
| K11 | 远程版本/公告 | 周期拉 net-conf，更新提示、公告、商店链接 | 无等价产品升级检查 | P3：版本提示即可 | [P15](#p15) / [W16](#w16) |
| K12 | 关于/赞赏/商店/反馈 | 已有作者页与外部链接 | README/文档与产品页不同 | 非求职核心；不迁移赞赏与作者反馈通道 | [P13](#p13) / [P15](#p15) / [W16](#w16) |
| K13 | 中英文 | manifest 有两套名称描述；主体 UI 中文 | 主体中文 | 两边都不能说完整多语言 | [P16](#p16) / [W16](#w16) |
| K14 | 菜单入口可达性 | Version 从 App 可达；AppMenu 整段挂载注释 | 侧边栏实际路由入口 | 按可达入口计功能，不按组件文件计 | [P01](#p01) / [P13](#p13) / [W16](#w16) |

### L. 存储、依赖、许可与扩展边界（11 项）

| 编号 | 能力 | 插件实际状态 | 作战台实际状态 | 差异与处理建议 | 证据 |
|---|---|---|---|---|---|
| L01 | 配置与数据保存 | 浏览器扩展 storage，图片 IndexedDB；部分按用户 ID 分区 | 本机 SQLite、素材文件、浏览器资料目录 | 保留本机持久数据；预设不是账号隔离 | [P05](#p05) / [P17](#p17) / [W05](#w05) / [W14](#w14) |
| L02 | 只读评估缓存 | 管理器有 TTL/LRU，正常 BOSS 列表调用注释 | 无同等统一 AI/地图评估缓存 | P3：在真实评估功能之后接入 | [P18](#p18) / [W11](#w11) |
| L03 | 缓存清理与业务去重 | 有清空缓存 UI | 账本不可因缓存清理而消失 | 必须分开两类状态 | [P13](#p13) / [P18](#p18) / [W05](#w05) |
| L04 | 浏览器权限/桥接 | storage/notifications，广域 host 权限，页面与后台桥 | 本机 API 与受控浏览器；新接口含本地来源检查 | 各自边界，不能据此断言全项目安全 | [P16](#p16) / [W02](#w02) / [W08](#w08) |
| L05 | 外部服务依赖 | 模型、高德、远程公告、聊天 WS 与上传接口 | 模型服务；企业微信 Bot 有代码未接业务；各招聘站点 | 单项失败需隔离与可见原因 | [P11](#p11) / [P15](#p15) / [P17](#p17) / [W11](#w11) / [W12](#w12) |
| L06 | 日志 UI 子模块 | 固定 devlog-ui 子模块并真正引入 Logs 页 | 无此依赖 | 借鉴搜索/诊断导出，不强制引入该库 | [P14](#p14) / [W18](#w18) |
| L07 | 协议/压缩第三方库 | 聊天 schema 与 builder 能力多于实际调用 | 页面自动化不依赖同一协议库 | 只统计产品调用到的文字/图片 | [P17](#p17) / [W04](#w04) |
| L08 | 许可证与 README | LICENSE 是 MIT，README 又有禁止商业用途文字 | 本项目另有自己的许可与作者代码 | 复制/分发前核实；本次独立产品建议 | [P19](#p19) / [W16](#w16) |
| L09 | 公司风险/已读/消息提醒 | 公司风险、阻止已读、BOSS 消息弹窗在待办 | 无同等已接入能力 | 不可列为直接复用的现成功能 | [P19](#p19) / [W01](#w01) |
| L10 | 反馈/面试转化闭环 | 扫描/沟通统计不是已读回复面试漏斗 | 账本确认也不是招聘方回复/面试结果 | 两边共有缺口，后续独立需求 | [P12](#p12) / [W05](#w05) / [W07](#w07) |
| L11 | 隐私文档与实际数据路径 | PRIVACY 仍提账号切换；功能已改预设，另有模型/地图/公告请求 | 本机数据不等于外部模型/机器人调用不出网 | 按实际调用说明数据去向，文档声明不能代替源码 | [P21](#p21) / [P11](#p11) / [P15](#p15) / [W11](#w11) / [W12](#w12) |

## 4. 需要特别辨别的源码差异

### 4.1 同公司、同 HR 名称与实际去重键不同

SameCompanyFilter 与 SameHrFilter 都检查并保存 jobData.key，而转换函数把 key 定义为 boss::encryptJobId。这实际仍是岗位维度，不能保证同一个 HR 的不同岗位或同公司的不同岗位被去重。两边都不能被宣传成已有完整的跨平台公司去重。依据：[P01](#p01)、[P03](#p03)、C03–C09。

落地要求：HR 使用平台稳定 HR 标识；公司使用可靠主体标识，并区分品牌名、主体、工作团队。拿不到可靠标识时明确能力范围；冷却期不得清除未知外发的账本保护。参考实现的过期清理发生在 saveSet，loadSet 读取时没有按有效期筛除，不能把配置里的过期时间直接当作精确生效的冷却策略。

### 4.2 插件在筛选步骤后就可能写入重复记录

去重处理器自己的 after 回调会写入集合，executeTask 按 before、handler、after 执行，然后才进入后续筛选与发布阶段。所以集合里出现记录不等于已经成功联系。当前作战台的预留和动作记录更适合作为外发事实来源。依据：[P03](#p03)、[P04](#p04)、[W05](#w05)。

### 4.3 好友过滤开关和已沟通过滤不是一回事

isFriend 在列表转换中被写为 false，检查到的详情转换更新在线/认证信息，却没有回填该好友状态。jobFriendStatus 只在值为 true 时过滤。与此同时，流程首部直接依据 rawData.jobitem.contact 跳过已沟通岗位，不依赖该开关。因此不能承诺“关闭好友过滤就会重新联系全部已聊 HR”。依据：[P01](#p01)、[P02](#p02)、[P03](#p03)。

### 4.4 AI 与普通招呼语同时启用可能各发一次

帮助文字描述 AI 替代普通话术，但两个处理器分别检查自己的 enable；流程同时注册两者，未看到强制互斥。应把“仅固定模板/仅 AI/本人确认后的模式”写成明确枚举，并让最终预览与真正发送使用同一模式。依据：[P02](#p02)、[P03](#p03)。

### 4.5 完成工作流次数不能代表真实占用的沟通次数

插件在整个岗位流程未被 skip 时累计 success，以其判断 deliveryLimit。若发布已成功而随后获取聊天资料、生成/发送文字或图片失败，沟通可能发生过，但整体未计成功。作战台按尝试、预留、UNKNOWN 和分动作记录处理；新增统计只能建立在这些事实之上。依据：[P04](#p04)、[P12](#p12)、[W05](#w05)、[W06](#w06)。

### 4.6 “有频控异常类”不保证整个任务会暂停

插件请求层定义 LimitError、RateLimitError 等，但工作流 execute 的每岗位 catch 会把异常变为该岗位 error；executeAll 后续仍可能继续处理列表。是否在真实网站条件下全面停止需运行验证，不能仅凭类名认定已经解决。我们的方案应把“正常无匹配、单岗位失败、平台当日限制、验证码、频率限制”映射到不同任务终态或阻断状态。依据：[P04](#p04)、[P17](#p17)。

发布写请求的网络异常还有递归重试；超时不证明平台没收到。不得照搬到本机无人值守投递中。点击/发出之后的未知结果继续按现有 UNKNOWN 流程保护。

### 4.7 薪资匹配语义与无法识别时的行为不同

插件区间逻辑提供相交与范围包含；薪资处理器只在文本命中已识别单位时检查相应区间，其他描述可能没有任何分支命中而通过。月薪与日薪/时薪同步是固定算式，不是实际收入证明。作战台硬月薪条件无法确认时不放行，属于应保留的差异。依据：[P03](#p03)、[P13](#p13)、[W01](#w01)。

### 4.8 HR 活跃、岗位更新、公司活跃的时间字段不能混用

插件列表转换使用 lastModifyTime 填 activeTime，详情转换又使用 brandComInfo.activeTime，并保留 bossInfo.activeTimeDesc。字段来自不同对象，不能直接统称为“HR 最后上线时间”。统一规则应优先展示平台原始 HR 描述和来源，字段含义不明时标未知。依据：[P01](#p01)、[P03](#p03)。

### 4.9 模型测试支持的参数不一定进入正式流程

模型测试通过 openai.createModel，可选择 Responses/ChatCompletions 并处理额外请求头；正式 createAgent 则创建 provider.chat，明确传入的参数少于表单字段。采样、工具、extra_body 等不能因表单存在就认定正式生效。超时文案和不同调用路径的数字/单位也需要统一。依据：[P08](#p08)、[P10](#p10)。

提示词测试按钮的停止主要影响后续任务，不等于已经取消所有在途请求。第一版若提供取消，必须明确“停止新增测试”和“中断在途请求”的区别。

### 4.10 AI 侧栏输入框不是已接通的自由聊天或自动回复

侧栏呈现岗位 JD、模型输出与处理过程；底部输入框绑定 pendingMessages，实际发送流程在延迟后读取它，用于修改即将发送的文字。没有把输入框提交事件接成完整的用户自由问答流程；自动回复入口也被 disabled。不要把“有聊天框”解读成“已经会回复 HR”。依据：[P01](#p01)、[P08](#p08)、[P13](#p13)。

### 4.11 地图提示词示例和真实数据结构有差异

地址 UI 中的示例是 amap.straightDistance 等路径，实际处理器把结果放在 state.amap.geocode 与 state.amap.distance；模板渲染只按传入对象逐层取值，不自动生成顶层 amap 别名。因此需要先定义变量合同再复用，不能把帮助示例视为已验证数据。高德直线/驾车/步行也不代表公交、地铁接入。依据：[P03](#p03)、[P07](#p07)、[P11](#p11)。

### 4.12 缓存管理器不等于缓存已进入正常流程

有 TTL、LRU、清空与工具函数，但 BOSS 列表入口的缓存读取被注释，检查到的正常工作流未调用这些缓存工具形成完整读写闭环。新开发应以岗位 ID、JD 内容版本、方案版本、模型/提示词版本组成只读评估缓存身份；它不承担投递去重。依据：[P18](#p18)、[W05](#w05)。

### 4.13 日统计和“近几日”不能直接搬入常驻服务

插件日期在 useStatistics 创建时确定，更新主要在挂载时触发；周期卡片按历史数组条数累计，而不是对每条日期做自然日窗口过滤。长时间跨午夜或中间隔天没有运行时，界面文字与实际窗口需要额外验证。作战台已有北京时间额度，应在同一日历口径上实现汇总。依据：[P12](#p12)、[W06](#w06)。

### 4.14 菜单、选项页、配置残留和依赖 API 必须按可达性计算

AppMenu 组件里有首次协议、日志/存储菜单，但 BOSS onMount 中创建该菜单元素的代码被注释，两个菜单项自身还被 disabled；不能算默认可用功能。Version 从 App 中仍有入口，应单独保留。

扩展 options/index.html 的 body 是占位，没有完整设置界面。aiReply 被禁用、record 入口被注释、changeBackground 仅见配置字段。日志依赖提供网络/错误捕获和时间线等 API，不代表本插件已经安装这些捕获或展示对应视图。依据：[P01](#p01)、[P13](#p13)、[P14](#p14)、[P16](#p16)。

### 4.15 作战台通知代码存在，但业务尚未接通

全局检索 Bot.、sendMessageByTime、sendMessageInstance，业务源码中只发现 Bot 类内部定义与自调用，没有 worker/任务流程调用。环境页标注企业微信群机器人；Bot 消息结构也是企业微信格式。飞书反馈表是插件作者的反馈通道，不是我们的用户通知能力。依据：[W12](#w12)、[P15](#p15)及原 PRD 的 E7/FR-16–FR-19。

### 4.16 声明、许可与实际数据去向需要一致

参考 LICENSE 为 MIT，README/首次协议文字另写禁止商业用途；隐私说明还提到已废弃的账号切换，而实际代码会按配置调用模型/地图并获取远程公告。此处记录源码与文档差异，不作法律结论。若直接复制或分发代码，应核实许可适用边界并保留版权说明；本次建议以现有结构独立实现。依据：[P19](#p19)、[P21](#p21)。

### 4.17 统一方案与旧平台设置仍有叠加关系

JobProfileRuntime 清空了若干旧搜索条件，并关闭 AI/重复沟通，但没有清空所有旧开关；旧平台黑名单仍然生效，BOSS 的 filterDeadHR 等也未在统一方案中成为独立字段。方案页已经提示原平台黑名单继续生效，但统一预览只展示采集到的样本及通用规则结果，不能据此还原所有在采集前已被旧条件排除的岗位。依据：[W03](#w03)、[W04](#w04)、[W10](#w10)。

因此新增高级规则时必须列明“统一方案条件、平台仍生效条件、紧急禁止”的优先关系与来源。不能只添加新字段，却让实际执行同时受隐藏的旧条件影响。

### 4.18 只读预览是有限样本，不是所有候选岗位的承诺

当前 JobPreviewCollector 为每个平台采集至多约 10 个样本，并设有时间上限；它会走专用 collect-only 分支。预览成功仅证明该版本在这批样本上的采集与判断流程，不证明当天所有岗位都会满足条件，也不是可长期编辑的全量候选队列。插件卡片主要对应当前页面列表，也不等于持久岗位库。依据：[W03](#w03)、[P01](#p01)。

新增候选列表需要另外定义采集批次、去重、过期、分页、规则版本和重新评估。不能把当前预览数组直接当作每日待投队列，更不能由“预览采集过”推导“已投递”。

## 5. 哪些能力应保留、哪些值得开发

### 5.1 作战台已有基础：继续完善即可

- 两平台可信账本、动作分开确认、未知结果核实、重启恢复和每日额度。
- 并行资源占用、每平台浏览器资料/执行隔离、全部停止和强制停止。
- 统一方案版本、只读预览、材料确认、执行前规则与紧急禁止。
- 平台岗位分析、CSV 导出、记录筛选分页、首页错误和过期状态。
- 明暗主题、移动导航与当前已统一的页面组件。

### 5.2 借鉴第一批：高级筛选与原因解释

**建议范围：**先接 BOSS 的工作内容必须包含/排除、公司白名单，以及命中规则与原文展示；把候选筛选事实和真实投递账本分开保存。已有经验/学历等条件如果纳入本批，必须在统一方案中显式声明，不能偷偷继承旧配置。

**建议切片：**

1. 扩展统一候选事实，保存 JD、地址、HR 活跃等来源与缺失状态；先把当前能可靠采集的字段接通。
2. 扩展方案字段与校验，保留旧版本兼容；修改仍产生新草稿。
3. 提供共享规则评估结果：是否通过、规则编号、原因、原文、方案版本；预览与外发前共用。
4. 在 worker 中接入筛选结果与只读原因记录，不把筛选通过记为投递成功。
5. 在求职方案预览中展示依据；记录页增加清晰的候选筛选入口，沿用现有环境配置 UI 组件。
6. 为智联逐项补对应事实；不支持的硬条件在保存/确认时明确阻止，不能静默忽略。

**验收：**命中排除词时零外发；否定语境示例可解释；缺失硬字段不通过；预览与执行一致；不同方案有各自原因；统计不重复累计；现有账本和额度规则仍成立。

可能涉及：JobCandidate、JobProfile、JobProfileService、JobPreviewCollector、两平台 worker、规则评估/筛选记录服务与接口、方案页与原因列表。范围需要分切片，不能当成只改几个输入框。

### 5.3 借鉴第二批：重复接触控制与活跃策略

**建议范围：**优先可靠 HR 标识的冷却期，再考虑可选的公司接触上限；把活跃阈值作为有来源的条件。

**前置条件：**确认实际能稳定取得哪些 HR/公司标识；按平台隔离；公司品牌与主体口径明确。拿不到标识时降低功能范围，而不是凭名字猜测。

**验收：**同 HR 不同岗位受控、不同 HR 不误伤；未知动作继续保护；重启、跨日与方案切换不绕过；停止后不会新增接触。公司策略默认可选，不能永久自动封掉所有团队。

### 5.4 借鉴第三批：方案库、模板与 AI 测试

**建议范围：**命名方案复制/差异、变量话术预览、多模型配置与连接测试、只生成不发送的岗位样本测试、按岗位查看评分依据。

**前置条件：**确定性规则和可解释结果先稳定；模型结果有结构校验、版本和调用预算。正式使用的模型参数必须与测试一致。

**验收：**测试不触发投递；未解析变量不能发送；超时和格式错误可见；导出不混入凭据；切换模型/模板不改正在运行任务。

### 5.5 后续可选：地图、评估缓存、引导和诊断包

先文字地区，再地图估算；先评估功能，再版本化缓存；先业务错误可操作，再更复杂的日志界面。计算器伪装、作者赞赏/商店入口、原站布局改造不列入求职核心开发。

### 5.6 原有无人值守主线仍按 PRD 交付

阶段 B：停止/暂停/接管语义、跨日阻断、飞书配置测试、事件合并与发送重试、失败可见。当前有若干控制接口，并不代表阶段 B 全部完成。

阶段 C：本机每日时间窗口、日轮唯一身份、常驻运行、重启/睡眠/漏跑、每日状态与飞书日报；前一阶段验收后再做。当前的登录检查定时器不等于这套业务调度。

如果以“每天没时间”为最先满足的目标，主线 B/C 比外观和复杂模型参数更紧迫；如果下一轮专门落地本次借鉴，建议选择 5.2 的首批范围。

## 6. 开发前应确定的产品边界

- 高级条件放求职方案，模型/地图/飞书连接放环境或对应连接配置；保持已有 UI 一致性。
- 一个生效方案决定本次条件；页面明确显示版本、材料和本平台不支持的字段。
- 硬条件、偏好、未知信息分开；AI 不把缺失事实补成符合要求。
- 候选采集/筛选、发起尝试、动作确认、招聘方回复/已读/面试是不同事实。
- 重新筛选只影响评估，不删除投递账本、不自动重发 UNKNOWN。
- 任务/账号/模型/方案相互隔离；多配置不伪装成多账号。
- 外发前仍校验停止、紧急禁止、材料与额度；新功能不能只在预览检查。
- 工具提示、静态代码和本地隔离结果不当作当前招聘网站真实兼容证明。

## 7. 本次完成了什么，以及未验证边界

已完成：固定源码版本、全量配置/模块与本地入口清点、172 项对照、关键调用链复核、未接线/禁用/占位识别、开发范围与验收建议。

本轮没有安装插件、操作真实招聘账号、触发投递/聊天、调用收费模型/地图、发送机器人消息或运行项目测试；源代码兼容性、平台字段持续可用性和外部消息确认仍需在选定开发范围之后验证。本轮新增的是调研文档与证据文件，没有改业务实现和既有任务计划。

“完整”的范围是本文固定版本与登记入口；未来提交、未执行的远端服务行为、压缩第三方库内部和真实账号运行结果不以猜测填充。

## 8. 证据索引

<a id="p01"></a>

### P01 BOSS 平台适配与数据映射

[src/entrypoints/boss/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/boss/index.ts)。

<a id="p02"></a>

### P02 BOSS 实际任务流水线

[src/entrypoints/boss/delivery.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/boss/delivery.ts)。

<a id="p03"></a>

### P03 筛选与招呼语处理器

[src/composables/useApplying/handles.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useApplying/handles.ts)；[src/composables/useApplying/utils.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useApplying/utils.ts)。

<a id="p04"></a>

### P04 工作流执行、停止与结果累计

[src/composables/useApplying/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useApplying/index.ts)；[src/composables/useApplying/type.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useApplying/type.ts)。

<a id="p05"></a>

### P05 业务配置默认值、保存、预设与迁移

[src/composables/conf/info.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/conf/info.ts)；[src/composables/conf/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/conf/index.ts)。

<a id="p06"></a>

### P06 完整配置类型

[src/types/formData.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/types/formData.ts)。

<a id="p07"></a>

### P07 多段话术、图片与模板变量

[src/components/Tabs/ConfigItem/CustomGreeting.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/ConfigItem/CustomGreeting.vue)；[src/utils/ai.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/utils/ai.ts)；[src/message/background.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/message/background.ts)。

<a id="p08"></a>

### P08 AI 正式执行路径与过程侧栏

[src/composables/useModel/chatModel.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useModel/chatModel.ts)；[src/components/ChatBox.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/ChatBox.vue)。

<a id="p09"></a>

### P09 提示词编辑、变量、样本测试

[src/components/AI/LLMPromptEdit.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/AI/LLMPromptEdit.vue)。

<a id="p10"></a>

### P10 模型管理、参数与连接测试

[src/components/AI/LLMModelManage.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/AI/LLMModelManage.vue)；[src/components/AI/LLMModelEdit.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/AI/LLMModelEdit.vue)；[src/composables/useModel/openai.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useModel/openai.ts)；[src/composables/useModel/common.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useModel/common.ts)；[src/composables/useModel/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useModel/index.ts)。

<a id="p11"></a>

### P11 地址设置与地图服务

[src/components/Tabs/ConfigItem/Address.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/ConfigItem/Address.vue)；[src/utils/amap.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/utils/amap.ts)。

<a id="p12"></a>

### P12 统计展示与持久累计

[src/components/Tabs/Statistics.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/Statistics.vue)；[src/composables/useStatistics.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useStatistics.ts)。

<a id="p13"></a>

### P13 页面、卡片、配置、外观与可达入口

[src/App.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/App.vue)；[src/AppMenu.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/AppMenu.vue)；[src/components/JobCard.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/JobCard.vue)；[src/components/JobCards.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/JobCards.vue)；[src/components/Tabs/Config.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/Config.vue)；[src/components/Tabs/ConfigItem/Appearance.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/ConfigItem/Appearance.vue)；[src/components/Tabs/ConfigItem/SalaryRange.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/ConfigItem/SalaryRange.vue)；[src/components/Tabs/AI.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/AI.vue)；[src/components/Alert.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Alert.vue)；[src/components/Tabs/Filter.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/Filter.vue)。

<a id="p14"></a>

### P14 日志入口、日志依赖与通知工具

[src/components/Tabs/Logs.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/Logs.vue)；[src/utils/logger.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/utils/logger.ts)；[src/composables/useHelper/ctx.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useHelper/ctx.ts)；[src/utils/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/utils/index.ts)。

实际使用的日志依赖：[src/ui/overlay.ts](https://github.com/Ocyss/devlog-ui/blob/2754bee64a7fc263b287926123acefc39d77107e/src/ui/overlay.ts)；[src/ui/filter.ts](https://github.com/Ocyss/devlog-ui/blob/2754bee64a7fc263b287926123acefc39d77107e/src/ui/filter.ts)；[src/core/logger.ts](https://github.com/Ocyss/devlog-ui/blob/2754bee64a7fc263b287926123acefc39d77107e/src/core/logger.ts)；[src/core/persistence.ts](https://github.com/Ocyss/devlog-ui/blob/2754bee64a7fc263b287926123acefc39d77107e/src/core/persistence.ts)；[src/index.ts](https://github.com/Ocyss/devlog-ui/blob/2754bee64a7fc263b287926123acefc39d77107e/src/index.ts)。

<a id="p15"></a>

### P15 远程公告、版本、商店与反馈

[src/composables/useHelper/netConf.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useHelper/netConf.ts)；[net-conf.json](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/net-conf.json)；[src/components/Menu/Version.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Menu/Version.vue)；[src/components/Tabs/About.vue](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/components/Tabs/About.vue)。

<a id="p16"></a>

### P16 扩展权限、注入与后台桥

[wxt.config.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/wxt.config.ts)；[src/entrypoints/content.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/content.ts)；[src/entrypoints/background.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/background.ts)；[src/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/index.ts)；[src/composables/useVue.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/useVue.ts)；[src/message/contentScript.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/message/contentScript.ts)；[src/message/contentScriptShare.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/message/contentScriptShare.ts)。

<a id="p17"></a>

### P17 发布请求、上传与聊天协议调用

[src/entrypoints/boss/requests.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/boss/requests.ts)；[src/entrypoints/boss/chat/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/boss/chat/index.ts)；[src/entrypoints/boss/chat/geek-chat-core.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/boss/chat/geek-chat-core.ts)；[src/assets/chat.proto](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/assets/chat.proto)。

<a id="p18"></a>

### P18 缓存代码及调用边界

[src/composables/usePipelineCache.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/composables/usePipelineCache.ts)；[src/types/pipelineCache.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/types/pipelineCache.ts)；[src/entrypoints/boss/index.ts](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/src/entrypoints/boss/index.ts)。

<a id="p19"></a>

### P19 功能说明、待办与许可

[README.md](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/README.md)；[LICENSE](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/LICENSE)。

<a id="p20"></a>

### P20 扩展打包与发布

[.github/workflows/main.yml](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/.github/workflows/main.yml)；[scripts/submit.sh](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/scripts/submit.sh)；[package.json](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/package.json)。

<a id="p21"></a>

### P21 项目隐私说明

[PRIVACY.md](https://github.com/Ocyss/boss-helper/blob/6da16a217e602f09aae3a5cee05f5a5caf0cb797/PRIVACY.md)。

<a id="w01"></a>

### W01 统一规则与候选字段

[src/main/java/com/getjobs/application/service/JobProfile.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/JobProfile.java)；[src/main/java/com/getjobs/application/service/JobCandidate.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/JobCandidate.java)。

<a id="w02"></a>

### W02 草稿、生效、预览、确认与紧急禁止

[src/main/java/com/getjobs/application/service/JobProfileService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/JobProfileService.java)；[src/main/java/com/getjobs/application/controller/JobProfileController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/JobProfileController.java)；[front/app/job-profile/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/job-profile/page.tsx)。

<a id="w03"></a>

### W03 统一方案映射与只读采集

[src/main/java/com/getjobs/application/service/JobProfileRuntime.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/JobProfileRuntime.java)；[src/main/java/com/getjobs/application/service/JobPreviewCollector.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/JobPreviewCollector.java)。

<a id="w04"></a>

### W04 BOSS 实际执行和原平台选项

[src/main/java/com/getjobs/worker/boss/Boss.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/boss/Boss.java)；[src/main/java/com/getjobs/worker/boss/BossConfig.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/boss/BossConfig.java)。

<a id="w05"></a>

### W05 两平台可信账本

[src/main/java/com/getjobs/application/service/BossDeliveryLedger.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/BossDeliveryLedger.java)；[src/main/java/com/getjobs/application/service/ZhilianDeliveryLedger.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/ZhilianDeliveryLedger.java)。

<a id="w06"></a>

### W06 额度、记录查询与界面

[src/main/java/com/getjobs/application/service/DailyQuotaService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/DailyQuotaService.java)；[src/main/java/com/getjobs/application/service/DeliveryReadService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/DeliveryReadService.java)；[src/main/java/com/getjobs/application/controller/DeliveryRecordsController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/DeliveryRecordsController.java)；[front/app/delivery-records/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/delivery-records/page.tsx)。

<a id="w07"></a>

### W07 作战台聚合与首页

[src/main/java/com/getjobs/application/controller/WorkbenchController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/WorkbenchController.java)；[front/app/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/page.tsx)；[front/app/components/PlatformCard.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/components/PlatformCard.tsx)。

<a id="w08"></a>

### W08 浏览器隔离、并行占用与任务协调

[src/main/java/com/getjobs/worker/manager/PlaywrightManager.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/manager/PlaywrightManager.java)；[src/main/java/com/getjobs/worker/manager/PlatformBrowserSession.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/manager/PlatformBrowserSession.java)；[src/main/java/com/getjobs/worker/manager/PlatformTaskGate.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/manager/PlatformTaskGate.java)；[src/main/java/com/getjobs/worker/service/PlatformTaskCoordinator.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/service/PlatformTaskCoordinator.java)。

<a id="w09"></a>

### W09 启动前预检

[src/main/java/com/getjobs/worker/service/PlatformPreflightService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/service/PlatformPreflightService.java)；[front/lib/preflight.ts](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/lib/preflight.ts)；[front/app/components/PreflightDialog.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/components/PreflightDialog.tsx)。

<a id="w10"></a>

### W10 BOSS 旧配置与黑名单

[src/main/java/com/getjobs/application/controller/BossConfigController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/BossConfigController.java)；[src/main/java/com/getjobs/application/service/BossService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/BossService.java)；[front/app/boss/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/boss/page.tsx)。

<a id="w11"></a>

### W11 现有模型服务和 AI 表单

[src/main/java/com/getjobs/application/service/AiService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/AiService.java)；[src/main/java/com/getjobs/application/controller/AiConfigController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/AiConfigController.java)；[front/app/ai-config/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/ai-config/page.tsx)；[front/app/env-config/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/env-config/page.tsx)。

<a id="w12"></a>

### W12 企业微信格式 Bot 与环境配置

[src/main/java/com/getjobs/worker/utils/Bot.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/utils/Bot.java)；[src/main/java/com/getjobs/application/service/ConfigService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/ConfigService.java)；[front/app/env-config/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/env-config/page.tsx)。

<a id="w13"></a>

### W13 平台分析、图表和 CSV

[front/app/boss/analysis/AnalysisContent.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/boss/analysis/AnalysisContent.tsx)；[front/app/zhilian/analysis/AnalysisContent.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/zhilian/analysis/AnalysisContent.tsx)；[front/app/liepin/analysis/AnalysisContent.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/liepin/analysis/AnalysisContent.tsx)；[front/app/51job/analysis/AnalysisContent.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/51job/analysis/AnalysisContent.tsx)；[src/main/java/com/getjobs/application/controller/BossAnalyticsController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/BossAnalyticsController.java)。

<a id="w14"></a>

### W14 图片简历保存与格式检查

[src/main/java/com/getjobs/application/service/BossResumeImageService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/service/BossResumeImageService.java)。

<a id="w15"></a>

### W15 平台 API 与各平台 worker 入口

[src/main/java/com/getjobs/application/controller/PlatformStatusController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/PlatformStatusController.java)；[src/main/java/com/getjobs/application/controller/BossController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/BossController.java)；[src/main/java/com/getjobs/application/controller/ZhilianController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/ZhilianController.java)；[src/main/java/com/getjobs/application/controller/LiepinController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/LiepinController.java)；[src/main/java/com/getjobs/application/controller/JobController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/JobController.java)。

<a id="w16"></a>

### W16 导航、主题与公共布局

[front/app/components/Sidebar.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/components/Sidebar.tsx)；[front/app/layout.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/layout.tsx)；[front/app/globals.css](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/globals.css)。

<a id="w17"></a>

### W17 智联暂停、恢复和人工核实

[src/main/java/com/getjobs/worker/service/ZhilianJobService.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/service/ZhilianJobService.java)；[src/main/java/com/getjobs/application/controller/ZhilianSafetyController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/ZhilianSafetyController.java)；[front/app/zhilian/DeliverySafetyPanel.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/zhilian/DeliverySafetyPanel.tsx)。

<a id="w18"></a>

### W18 实时状态与进度通道

[front/lib/sse.ts](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/lib/sse.ts)；[src/main/java/com/getjobs/application/controller/BossController.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/application/controller/BossController.java)；[src/main/java/com/getjobs/worker/dto/JobProgressMessage.java](https://github.com/slowbuild-lab/offer-pilot/blob/main/src/main/java/com/getjobs/worker/dto/JobProgressMessage.java)。

<a id="w19"></a>

### W19 本机构建与许可文件

[start-local.command](https://github.com/slowbuild-lab/offer-pilot/blob/main/start-local.command)；[build.gradle.kts](https://github.com/slowbuild-lab/offer-pilot/blob/main/build.gradle.kts)；[front/package.json](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/package.json)；[LICENSE](https://github.com/slowbuild-lab/offer-pilot/blob/main/LICENSE)。

## 9. 全部配置结构覆盖表

以下包括字段、配置父节点和表单元数据，按真实声明自动提取。每条均映射到前文检查编号；没有未归属项。

| 配置结构 | 字段路径 | 检查编号 |
|---|---|---|
| defaultFormData | configLevel | K02 |
| defaultFormData | company | B05、B06 |
| defaultFormData | company.include | B05、B06 |
| defaultFormData | company.value | B05、B06 |
| defaultFormData | company.options | B05、B06 |
| defaultFormData | company.enable | B05、B06 |
| defaultFormData | jobTitle | B03、B04 |
| defaultFormData | jobTitle.include | B03、B04 |
| defaultFormData | jobTitle.value | B03、B04 |
| defaultFormData | jobTitle.options | B03、B04 |
| defaultFormData | jobTitle.enable | B03、B04 |
| defaultFormData | jobContent | B07、B08、B09 |
| defaultFormData | jobContent.include | B07、B08、B09 |
| defaultFormData | jobContent.value | B07、B08、B09 |
| defaultFormData | jobContent.options | B07、B08、B09 |
| defaultFormData | jobContent.enable | B07、B08、B09 |
| defaultFormData | hrPosition | B10 |
| defaultFormData | hrPosition.include | B10 |
| defaultFormData | hrPosition.value | B10 |
| defaultFormData | hrPosition.options | B10 |
| defaultFormData | hrPosition.enable | B10 |
| defaultFormData | jobAddress | B11 |
| defaultFormData | jobAddress.value | B11 |
| defaultFormData | jobAddress.options | B11 |
| defaultFormData | jobAddress.enable | B11 |
| defaultFormData | jobAddress.include | B11 |
| defaultFormData | salaryRange | B13、B14、B15、B16、B17 |
| defaultFormData | salaryRange.value | B13、B14、B15、B16、B17 |
| defaultFormData | salaryRange.advancedValue | B13、B14、B15、B16、B17 |
| defaultFormData | salaryRange.advancedValue.H | B13、B14、B15、B16、B17 |
| defaultFormData | salaryRange.advancedValue.D | B13、B14、B15、B16、B17 |
| defaultFormData | salaryRange.advancedValue.M | B13、B14、B15、B16、B17 |
| defaultFormData | salaryRange.enable | B13、B14、B15、B16、B17 |
| defaultFormData | companySizeRange | B12 |
| defaultFormData | companySizeRange.value | B12 |
| defaultFormData | companySizeRange.enable | B12 |
| defaultFormData | customGreeting | E01、E03、E04 |
| defaultFormData | customGreeting.value | E01、E03、E04 |
| defaultFormData | customGreeting.enable | E01、E03、E04 |
| defaultFormData | deliveryLimit | D10 |
| defaultFormData | deliveryLimit.value | D10 |
| defaultFormData | greetingVariable | E02 |
| defaultFormData | greetingVariable.value | E02 |
| defaultFormData | activityFilter | B20、B21 |
| defaultFormData | activityFilter.value | B20、B21 |
| defaultFormData | friendStatus | C01、C02 |
| defaultFormData | friendStatus.value | C01、C02 |
| defaultFormData | bossGoldMedalHr | B23 |
| defaultFormData | bossGoldMedalHr.value | B23 |
| defaultFormData | sameCompanyFilter | C04、C05、C06 |
| defaultFormData | sameCompanyFilter.value | C04、C05、C06 |
| defaultFormData | sameCompanyFilter.expire | C04、C05、C06 |
| defaultFormData | sameHrFilter | C03、C05、C06 |
| defaultFormData | sameHrFilter.value | C03、C05、C06 |
| defaultFormData | sameHrFilter.expire | C03、C05、C06 |
| defaultFormData | goldHunterFilter | B22 |
| defaultFormData | goldHunterFilter.value | B22 |
| defaultFormData | notification | J01 |
| defaultFormData | notification.value | J01 |
| defaultFormData | useCache | L02、L03 |
| defaultFormData | useCache.value | L02、L03 |
| defaultFormData | aiGreeting | E06、E07、F02 |
| defaultFormData | aiGreeting.enable | E06、E07、F02 |
| defaultFormData | aiGreeting.prompt | E06、E07、F02 |
| defaultFormData | aiFiltering | F03、F04 |
| defaultFormData | aiFiltering.enable | F03、F04 |
| defaultFormData | aiFiltering.prompt | F03、F04 |
| defaultFormData | aiFiltering.score | F03、F04 |
| defaultFormData | aiReply | F16 |
| defaultFormData | aiReply.enable | F16 |
| defaultFormData | aiReply.prompt | F16 |
| defaultFormData | amap | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.key | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.origins | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.straightDistance | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.drivingDistance | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.drivingDuration | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.walkingDistance | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.walkingDuration | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | amap.enable | G01、G02、G03、G04、G05、G06、G09 |
| defaultFormData | record | F17 |
| defaultFormData | record.enable | F17 |
| defaultFormData | delayDeliveryStarts | D04 |
| defaultFormData | delayDeliveryInterval | D05 |
| defaultFormData | delayDeliveryPageNext | D06 |
| defaultFormData | delayMessageSending | D07、E05 |
| defaultFormData | version | H08 |
| appearanceConf | hideHeader | K04 |
| appearanceConf | changeIcon | K05 |
| appearanceConf | dynamicTitle | K05 |
| appearanceConf | changeBackground | K09 |
| appearanceConf | blurCard | K06 |
| appearanceConf | listSink | K07 |
| appearanceConf | contentOffset | K07 |
| appearanceConf | leftChat | K08 |
| appearanceConf | chatBoxWidth | K08 |
| appearanceConf | defaultShowChatBox | K08 |
| info | mode | F19 |
| info | mode.mode | F19 |
| info | mode.label | F19 |
| info | avatar | F01 |
| info | avatar.type | F01 |
| info | avatar.format | F01 |
| info | avatar.required | F01 |
| info | base_url | F01、F19 |
| info | base_url.desc | F01、F19 |
| info | base_url.type | F01、F19 |
| info | base_url.config | F01、F19 |
| info | base_url.config.placeholder | F01、F19 |
| info | base_url.config.items | F01、F19 |
| info | base_url.config.createItem | F01、F19 |
| info | base_url.required | F01、F19 |
| info | api_key | F01、F20 |
| info | api_key.type | F01、F20 |
| info | api_key.required | F01、F20 |
| info | model | F01、F02 |
| info | model.config | F01、F02 |
| info | model.config.placeholder | F01、F02 |
| info | model.config.items | F01、F02 |
| info | model.config.createItem | F01、F02 |
| info | model.value | F01、F02 |
| info | model.type | F01、F02 |
| info | model.required | F01、F02 |
| info | responses | F12 |
| info | responses.value | F12 |
| info | responses.type | F12 |
| info | responses.desc | F12 |
| info | other | F14 |
| info | advanced | F13 |
| info | advanced.label | F13 |
| info | advanced.alert | F13 |
| info | advanced.desc | F13 |
| info | advanced.value | F13 |
| info | advanced.value.json | F13 |
| info | advanced.value.json.value | F13 |
| info | advanced.value.json.type | F13 |
| info | advanced.value.json.desc | F13 |
| info | advanced.value.json.config | F13 |
| info | advanced.value.json.config.disabled | F13 |
| info | advanced.value.stream | F13 |
| info | advanced.value.stream.value | F13 |
| info | advanced.value.stream.type | F13 |
| info | advanced.value.stream.desc | F13 |
| info | advanced.value.stream.config | F13 |
| info | advanced.value.stream.config.disabled | F13 |
| info | advanced.value.temperature | F13 |
| info | advanced.value.temperature.type | F13 |
| info | advanced.value.temperature.config | F13 |
| info | advanced.value.temperature.config.min | F13 |
| info | advanced.value.temperature.config.max | F13 |
| info | advanced.value.temperature.config.step | F13 |
| info | advanced.value.temperature.desc | F13 |
| info | advanced.value.top_p | F13 |
| info | advanced.value.top_p.type | F13 |
| info | advanced.value.top_p.config | F13 |
| info | advanced.value.top_p.config.min | F13 |
| info | advanced.value.top_p.config.max | F13 |
| info | advanced.value.top_p.config.step | F13 |
| info | advanced.value.top_p.desc | F13 |
| info | advanced.value.presence_penalty | F13 |
| info | advanced.value.presence_penalty.value | F13 |
| info | advanced.value.presence_penalty.type | F13 |
| info | advanced.value.presence_penalty.config | F13 |
| info | advanced.value.presence_penalty.config.min | F13 |
| info | advanced.value.presence_penalty.config.max | F13 |
| info | advanced.value.presence_penalty.config.step | F13 |
| info | advanced.value.presence_penalty.desc | F13 |
| info | advanced.value.frequency_penalty | F13 |
| info | advanced.value.frequency_penalty.type | F13 |
| info | advanced.value.frequency_penalty.config | F13 |
| info | advanced.value.frequency_penalty.config.min | F13 |
| info | advanced.value.frequency_penalty.config.max | F13 |
| info | advanced.value.frequency_penalty.config.step | F13 |
| info | advanced.value.frequency_penalty.desc | F13 |
| info | advanced.value.tool_choice | F13 |
| info | advanced.value.tool_choice.type | F13 |
| info | advanced.value.tool_choice.format | F13 |
| info | advanced.value.tool_choice.config | F13 |
| info | advanced.value.tool_choice.config.items | F13 |
| info | advanced.value.tool_choice.config.createItem | F13 |
| info | advanced.value.tool_choice.desc | F13 |
| info | advanced.value.tool_choice.condition | F13 |
| info | advanced.value.tools | F13 |
| info | advanced.value.tools.type | F13 |
| info | advanced.value.tools.format | F13 |
| info | advanced.value.tools.desc | F13 |
| info | advanced.value.tools.condition | F13 |
| info | advanced.value.extra_headers | F13 |
| info | advanced.value.extra_headers.type | F13 |
| info | advanced.value.extra_headers.format | F13 |
| info | advanced.value.extra_headers.desc | F13 |
| info | advanced.value.extra_body | F13 |
| info | advanced.value.extra_body.type | F13 |
| info | advanced.value.extra_body.format | F13 |
| info | advanced.value.extra_body.desc | F13 |

### 9.1 类型、数组元素和内部配置补充

前表从默认配置和模型表单对象自动提取。下表补充类型中可选字段、数组元素、预设元数据和内部配置，避免将“默认值未出现”误当作没有该字段。

| 补充结构 | 实际边界 | 对应检查 |
|---|---|---|
| aiGreeting.model / aiFiltering.model / aiReply.model | FormDataAi 中可选模型标识，默认对象可能不含 model | F02、F16 |
| ai*.prompt[].role / content | 消息数组中的角色与正文 | F05、F06 |
| record.model[] | 类型中有记录模型字段；入口未启用 | F17 |
| customGreeting.value[].type / content / image | 多段文字和图片消息的持久结构 | E02、E03、E04 |
| customGreeting.value[].model | 图片编辑时的临时 File；不是可直接 JSON 分享的文件数据 | E04、H07 |
| FormDataSelect.options[] / value[] / include / enable | 词条选项、已选值和包含/启用模式 | B24、H07 |
| FormDataRange[0/1/2] | 下限、上限、严格/宽松；第三位不是通用启用开关 | B14、B15 |
| FormDataPreset.value / FormDataPresets[].label / value | 当前预设标识与预设列表元数据 | H01、H02 |
| ModelConf.key / name / color / data | 模型条目标识、名称、外观和具体配置 | F01、F20 |
| OpenaiLLMConf.mode / avatar / base_url / api_key / model | 模型数据类型字段，对应表单分组 | F01、F19、F20 |
| OpenaiLLMConf.responses / other.timeout | 接口选择与超时，正式与测试路径须一致 | F12、F14 |
| advanced.json / stream / temperature / top_p | 输出与采样配置；字段存在不代表正式透传 | F11、F13 |
| advanced.presence_penalty / frequency_penalty | 采样惩罚参数声明 | F13 |
| advanced.tool_choice / tools[] | 工具配置声明；不等于完整 Agent 工具运行台 | F13 |
| advanced.extra_headers / extra_body | 额外认证头/请求体；正式路径未全部接通 | F13、F20 |
| PipelineCacheConfig.expireDays / cleanupInterval / storageKey / maxCacheSize / processorConfigs | 内部缓存配置，不是正常投递流程已使用的证明 | L02、L03 |
| processorConfigs.aiFiltering / amap / basic.expireTime | 按处理器分类的缓存有效期 | L02 |
| Statistics.date / success / total / repeat / activityFilter / tasks | 统计结构，状态计数不等于唯一岗位或真实外发次数 | I05–I09、I15 |
| appearanceConf 全部 10 个字段 | 全部列于前表；changeBackground 无实际入口 | K04–K09 |
| NetConf.version / version_description / notification / store / feedback / price_info | 远程配置类型；类型声明的 price_info 等不代表业务使用 | K11、K12 |

## 10. 插件运行文件逐项归属

清单来自固定提交的仓库树；类型、样式、辅助文件不虚构独立功能，未接线与第三方边界明确登记。

| 文件 | 对应检查范围 | 归属说明 |
|---|---|---|
| .gitmodules | J06、L06 | 日志依赖固定版本 |
| net-conf.json | K11、K12 | 远程公告与版本配置 |
| public/_locales/en/messages.json | A06、K13、L04 | 构建权限或清单本地化 |
| public/_locales/zh_CN/messages.json | A06、K13、L04 | 构建权限或清单本地化 |
| src/App.vue | I01、K01、K14 | 面板主入口 |
| src/AppMenu.vue | J13、K03、K14 | 组件存在，默认挂载未接通 |
| src/assets/chat.proto | E10–E12、L07 | 协议/构造器；不等于全部产品发送类型 |
| src/assets/main.css | K04–K09 | 样式和站点布局辅助 |
| src/components/AI/LLMForm.vue | F01–F14、F20 | 模型/提示词设置与测试 |
| src/components/AI/LLMFormItem.vue | F01–F14、F20 | 模型/提示词设置与测试 |
| src/components/AI/LLMModelEdit.vue | F01–F14、F20 | 模型/提示词设置与测试 |
| src/components/AI/LLMModelManage.vue | F01–F14、F20 | 模型/提示词设置与测试 |
| src/components/AI/LLMPromptEdit.vue | F01–F14、F20 | 模型/提示词设置与测试 |
| src/components/Alert.vue | J02、K10 | 提示与关闭记忆 |
| src/components/ChatBox.vue | E05、F10、F16 | AI 输出与待发送文本面板 |
| src/components/JobCard.vue | I01–I04 | 岗位卡片与状态筛选 |
| src/components/JobCards.vue | I01–I04 | 岗位卡片与状态筛选 |
| src/components/Menu/Log.vue | J13、K11、K14 | 版本或停用的菜单组件 |
| src/components/Menu/Store.vue | J13、K11、K14 | 版本或停用的菜单组件 |
| src/components/Menu/Version.vue | J13、K11、K14 | 版本或停用的菜单组件 |
| src/components/StoreIcons.tsx | K12 | 商店图标辅助，无独立求职能力 |
| src/components/Tabs/AI.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/About.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/Config.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/Address.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/Appearance.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/ConfigItem.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/CustomGreeting.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/Form/FormItem.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/Form/FormRange.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/Form/FormSelect.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/Form/FormSwitch.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/ConfigItem/SalaryRange.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/Filter.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/Logs.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/components/Tabs/Statistics.vue | B01–B24、F16–F17、G01–G09、H01–H09、I05–I09、K01–K12 | 按配置/展示入口纳入相应对照 |
| src/composables/conf/index.ts | B、C、D04–D07、E、F、G、H、K | 全部配置与迁移；逐字段覆盖见第 9 节 |
| src/composables/conf/info.ts | B、C、D04–D07、E、F、G、H、K | 全部配置与迁移；逐字段覆盖见第 9 节 |
| src/composables/useApplying/deliverError.ts | B、C、D、E、F03–F04 | 筛选与任务执行核心 |
| src/composables/useApplying/handles.ts | B、C、D、E、F03–F04 | 筛选与任务执行核心 |
| src/composables/useApplying/index.ts | B、C、D、E、F03–F04 | 筛选与任务执行核心 |
| src/composables/useApplying/type.ts | B、C、D、E、F03–F04 | 筛选与任务执行核心 |
| src/composables/useApplying/utils.ts | B、C、D、E、F03–F04 | 筛选与任务执行核心 |
| src/composables/useHelper/ctx.ts | A、D、I、J01、K11 | 平台上下文、通知、远程配置及类型 |
| src/composables/useHelper/index.ts | A、D、I、J01、K11 | 平台上下文、通知、远程配置及类型 |
| src/composables/useHelper/netConf.ts | A、D、I、J01、K11 | 平台上下文、通知、远程配置及类型 |
| src/composables/useHelper/type.ts | A、D、I、J01、K11 | 平台上下文、通知、远程配置及类型 |
| src/composables/useModel/chatModel.ts | F01–F20 | 模型配置和正式执行参数 |
| src/composables/useModel/common.ts | F01–F20 | 模型配置和正式执行参数 |
| src/composables/useModel/index.ts | F01–F20 | 模型配置和正式执行参数 |
| src/composables/useModel/openai.ts | F01–F20 | 模型配置和正式执行参数 |
| src/composables/useModel/type.ts | F01–F20 | 模型配置和正式执行参数 |
| src/composables/usePipelineCache.ts | L02–L03 | 缓存实现和未完整接线边界 |
| src/composables/useStatistics.ts | I05–I09、I15 | 日期和累计统计 |
| src/composables/useVue.ts | A04、D08、L04 | 读取原站内部 Vue 数据/函数 |
| src/entrypoints/background.ts | A01、A05–A07、L04 | 注入、主挂载或后台桥 |
| src/entrypoints/boss/chat/geek-chat-core.2.0.4.umd.min.js | E10、E12、L07 | 第三方压缩协议依赖：用途登记，未逐行审计 |
| src/entrypoints/boss/chat/geek-chat-core.ts | E10–E12、L07 | 协议/构造器；不等于全部产品发送类型 |
| src/entrypoints/boss/chat/index.ts | E10–E12、L07 | 协议/构造器；不等于全部产品发送类型 |
| src/entrypoints/boss/delivery.ts | B、C、D、E | 已注册任务与先后顺序 |
| src/entrypoints/boss/index.ts | A、C、D、E、I | BOSS 实际入口、数据映射、发送 |
| src/entrypoints/boss/inject.css | K04–K09 | 样式和站点布局辅助 |
| src/entrypoints/boss/requests.ts | D14–D15、E04、E10–E11 | 发布请求、重试和上传 |
| src/entrypoints/boss/types.ts | B、E、F、G | 数据类型，不独立算运行功能 |
| src/entrypoints/content.ts | A01、A05–A07、L04 | 注入、主挂载或后台桥 |
| src/entrypoints/options/index.html | A01、K14 | 独立选项 HTML 为占位 |
| src/env.d.ts | B、E、F、G | 数据类型，不独立算运行功能 |
| src/index.ts | A01、A05–A07、L04 | 注入、主挂载或后台桥 |
| src/message/background.ts | E04、J01、L01、L04 | 存储、通知、图片与消息桥 |
| src/message/contentScript.ts | E04、J01、L01、L04 | 存储、通知、图片与消息桥 |
| src/message/contentScriptShare.ts | E04、J01、L01、L04 | 存储、通知、图片与消息桥 |
| src/message/index.ts | E04、J01、L01、L04 | 存储、通知、图片与消息桥 |
| src/types/formData.ts | B、C、D、E、F、G、H | 完整配置声明，未把类型当实现 |
| src/types/pipelineCache.ts | L02–L03 | 缓存实现和未完整接线边界 |
| src/utils/ai.ts | E02、F03–F06 | 变量渲染及 JSON 解析辅助 |
| src/utils/amap.ts | G01–G09 | 地图计算 |
| src/utils/deepmerge.ts | H04–H08 | 配置合并辅助 |
| src/utils/elmGetter.ts | A04、D08、K04 | 页面元素查找/移除辅助 |
| src/utils/file.ts | E04 | 图片内容 MD5 辅助 |
| src/utils/index.ts | D04–D07、I15、J01 | 通知、等待、日期和错误辅助 |
| src/utils/jsonImportExport.ts | H07、F20 | JSON 分享 |
| src/utils/logger.ts | J06–J12 | 日志容量、级别和持久配置 |
| src/utils/request.ts | D15、F14、L04 | 请求与错误/超时辅助 |
| wxt.config.ts | A06、K13、L04 | 构建权限或清单本地化 |

日志子模块另外检查的 14 个运行文件：

- src/channel/broadcast.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/core/diff.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/core/error-capture.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/core/logger.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/core/network-capture.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/core/persistence.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/core/types.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/index.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/noop.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/ui/filter.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/ui/log-entry.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/ui/overlay.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/ui/styles.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。
- src/ui/timeline.ts：纳入 J06–J12 / L06；是否由插件调用按入口区分。

根文件另含 README、LICENSE、package.json、PRIVACY.md、.github/workflows/main.yml、scripts/submit.sh：分别纳入 A11、F19、K11–K12、L08、L11。

## 11. 作战台页面入口与辅助证据

- [front/app/51job/analysis/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/51job/analysis/page.tsx)。
- [front/app/51job/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/51job/page.tsx)。
- [front/app/ai-config/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/ai-config/page.tsx)。
- [front/app/boss/analysis/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/boss/analysis/page.tsx)。
- [front/app/boss/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/boss/page.tsx)。
- [front/app/delivery-records/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/delivery-records/page.tsx)。
- [front/app/env-config/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/env-config/page.tsx)。
- [front/app/job-profile/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/job-profile/page.tsx)。
- [front/app/liepin/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/liepin/page.tsx)。
- [front/app/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/page.tsx)。
- [front/app/zhilian/analysis/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/zhilian/analysis/page.tsx)。
- [front/app/zhilian/page.tsx](https://github.com/slowbuild-lab/offer-pilot/blob/main/front/app/zhilian/page.tsx)。

猎聘分析内容作为平台页内内容引入，不额外虚构一个不存在的 page.tsx 路由。16 个控制器和 117 处 Mapping 声明的逐文件位置保存在本地证据清单中；这些接口只做源码清点，本轮未调用登录、启动、停止、诊断导航、模型生成或任何写入接口。

- 完整结构化差异、字段映射与仓库清单（本地验收记录，不随仓库分发）。
- 作战台源码哈希、页面与接口声明清单（本地验收记录，不随仓库分发）。
- 本次配置结构自动提取结果（本地验收记录，不随仓库分发）。
- 本次固定版本取证说明（本地验收记录，不随仓库分发）。

## 12. 待本人选择是否开发

本次到分析与文档交付为止，后续开发需由你选定一批范围：

1. **先开发插件借鉴首批**：BOSS 工作内容包含/排除、公司白名单、命中原文与筛选原因明细；先 BOSS，之后逐项接智联。
2. **优先继续无人值守主线**：按原 PRD 先完成阶段 B 的任务控制与飞书，验收后再做阶段 C 每日计划。
3. **先保留文档**：暂不开发，按检查编号继续调整范围。

是否开始开发；若开始，选择哪一批？
