# 手机录入到飞书多维表格

这是一个 Android 端 AI 录入工具：将手机截图分享到 App，由视觉模型识别内容，用户确认后自动写入飞书多维表格。

项目最初用于支付截图记账，但能力并不限于账目。只要业务适合“在手机上采集或确认信息，再按字段同步到多维表格”，都可以在此基础上配置，例如报销登记、订单采集、库存记录、巡检上报、客户线索和活动报名等。

## 当前能力

- 从 Android 分享面板接收截图，或在手机端手工录入。
- 使用视觉模型从图片中提取结构化内容。
- 连接飞书多维表格，读取字段并将确认后的内容写入对应字段。
- 管理常用表格、字段显示顺序和单选/多选项。
- 支持支付截图、支付通知等记账快捷入口；这些属于示例场景，并非唯一用途。
- 通过私有配置部署自己的识别服务、飞书应用和数据表。

## 目录

- `android-app/`：Android 8+ Kotlin / Jetpack Compose 客户端。
- `worker/`：Cloudflare Worker API，代理百炼和飞书。
- `worker/migrations/`：Worker 数据结构迁移。
- `docs/`：隐私、版本发布和界面设计文档。
- `releases/`：已确认稳定的正式版本说明；APK 通过 GitHub Releases 发布。
- `publish/github/`：GitHub 发布规则，不复制第二套源码。

## 本地验证

```bash
cd worker
npm install
npm run typecheck
npm test

cd ../android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug
```

## 自己部署

自部署需要你自己创建 Cloudflare Worker、百炼 API Key 和飞书自建应用，再构建指向该 Worker 的 Android APK。

请按完整的 **[单人自部署教程](docs/SELF_HOSTING.md)** 操作，其中包含飞书权限、Cloudflare KV/D1、密钥配置、邀请码、APK 构建、验收和常见报错。

> 仓库不保存任何真实 API Key、飞书密钥、签名证书、个人账本或历史 APK。每位使用者都需要部署自己的服务并填写自己的配置。

正式版本见 [`releases/README.md`](releases/README.md)，发布与回退流程见 [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md)。调试包和中间过程只保存在本地，不进入 GitHub。
