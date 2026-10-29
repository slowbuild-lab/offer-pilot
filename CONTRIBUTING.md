# 参与贡献

反馈问题请到[本仓库Issues](https://github.com/slowbuild-lab/offer-pilot/issues)，附系统、提交版本、页面、操作步骤、期望与实际结果，以及已脱敏的日志。

提交Pull Request时以本仓库 `main` 为目标分支。说明修改解决的问题、涉及的平台、验证方法，以及哪些部分没有在真实账号上验证。

当前提交检查沿用“emoji + 小写英文 + 句点”的格式，例如：

```text
📝 docs: clarify delivery pacing settings.
🐛 fix: preserve unknown delivery results.
```

后端使用 `./gradlew test`；前端在 `front/` 执行 `npx tsc --noEmit` 和 `npm run build -- --webpack`；文档站在 `docs/` 执行 `npm ci` 与 `npm run docs:build`。浏览器执行验证使用隔离页面和临时数据库，不向真实招聘账号批量投递做压力测试。

不得提交数据库、Cookie、浏览器资料、真实简历、模型密钥或机器人地址。不要以删除失败测试、跳过测试或吞掉账本错误的方式通过检查。

尊重[LICENSE](LICENSE)与[NOTICE](NOTICE.md)，不要把引用或改造的功能写成从零原创，也不要以此仓库的名义提供未经授权的商业许可。贡献说明见[CLA.md](CLA.md)。
