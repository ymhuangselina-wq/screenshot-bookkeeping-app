# 发布与回退流程

## 版本状态

- 本地候选包：只用于调试和真机验证，不提交 GitHub。
- Git Tag：仅对应维护者明确确认的稳定版本。
- GitHub Releases：只公开发布经过真机验证的 APK、说明和校验值。

## 构建个人候选包

1. 在被 Git 忽略的 `android-app/local.properties` 中填写 `serviceUrl`。
2. 将签名配置保存在 `android-app/.signing/`。
3. 运行：

```bash
cd android-app
./gradlew testDebugUnitTest assembleRelease
```

4. 将 APK 放在 Git 忽略的本地目录中。
5. 在本地记录产品版本、versionCode、applicationId、签名兼容性、测试结果和 SHA-256。

## 转为稳定版

候选包真机验证通过后：

1. 获得维护者明确的稳定版确认。
2. 更新 `CHANGELOG.md` 和 `releases/README.md`。
3. 完成源码、Git 历史和 APK 的敏感信息扫描。
4. 提交源码并创建 Git Tag。
5. 在 GitHub Releases 上传 APK、发布说明和 `SHA256SUMS`。

## 回退

- 源码回退：检出对应 Git Tag。
- APK 回退：从 GitHub Releases 选择旧的正式版本。
- Android 通常不允许直接安装更低 versionCode；必要时先备份数据再卸载新版。
- applicationId 或签名不同的 APK 无法覆盖安装，可能会作为独立应用并存。
