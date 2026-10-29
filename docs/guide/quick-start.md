# 本机启动

## 环境

准备JDK 21、Node.js 24（含npm）、Google Chrome及桌面图形环境。使用仓库自带Gradle Wrapper。当前已验证环境为macOS，其他系统需自行验证。

## 获取代码与基础数据库

```bash
git clone https://github.com/slowbuild-lab/offer-pilot.git
cd get_jobs
cd front
npm ci
cd ..
```

**首次安装需先取得兼容的空数据库模板。** 当前仓库不提供完整初始化包，也没有自己的Release数据库附件。不能只创建空SQLite文件；缺少基础配置表和选项字典会导致运行错误。

将兼容模板命名为 `getjobs.db` 放到 `db/`。模板来源和升级要求见[本机运行说明](https://github.com/slowbuild-lab/offer-pilot/blob/main/本机运行说明.md)。已有用户保留旧库，不覆盖。

## 启动服务

macOS双击 `start-local.command`，或执行：

```bash
./start-local.command
```

手动启动：

```bash
./gradlew --console=plain bootRun --args='--server.address=127.0.0.1'
```

Windows将启动器改为 `gradlew.bat`。首次构建需要下载依赖，启动入口会准备驱动。不要同时运行两个服务实例。

管理页：<http://127.0.0.1:6866/>；后端：<http://127.0.0.1:9527/>。健康接口为 `/api/health`。

## 第一次任务

1. 打开对应平台浏览器，人工登录。
2. BOSS/智联保存方案草稿，预览并确认；其他平台填写自身配置。
3. 在环境配置核对节奏和预算。
4. 手动启动一个平台，查看保护记录及结果。

初次使用优先核对流程与匹配依据，不做真实账号压力测试。出现验证或不明确结果时先处理平台页面，不反复启动。
