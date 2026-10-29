# 反馈与贡献

反馈入口统一使用[本仓库Issues](https://github.com/slowbuild-lab/offer-pilot/issues)。当前文档不提供第三方群聊、收费代部署、广告合作或赞赏入口。

## 提供有效信息

说明系统、提交版本、平台、页面、操作步骤及实际结果。区分“页面打开”“登录成功”“规则通过”“外发确认”，避免只写“无法使用”。日志与截图请先脱敏。

## 提交修改

Pull Request以本仓库 `main` 为目标。描述问题、修改行为、检查方法和未验证范围。遵守当前提交格式检查，例：`📝 docs: clarify delivery controls.`。

后端运行 `./gradlew test`，前端检查类型并构建，文档执行 `npm run docs:build`。平台执行测试使用临时数据库与隔离页面，不批量操作真实招聘账号。

[完整贡献指南](https://github.com/slowbuild-lab/offer-pilot/blob/main/CONTRIBUTING.md) · [授权与来源](https://github.com/slowbuild-lab/offer-pilot/blob/main/NOTICE.md)
