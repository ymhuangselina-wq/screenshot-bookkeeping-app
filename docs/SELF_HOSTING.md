# 自部署教程（单人使用）

本教程用于把整套服务部署到你自己的账号下：Android App 只连接你的 Cloudflare Worker，Worker 使用你的百炼 API Key 识别图片，并使用你的飞书自建应用读写你授权的多维表格。

> 这不是“安装 APK 就能用”的公共服务。你需要自己准备 Cloudflare、阿里云百炼和飞书开放平台账号。优点是 API Key、飞书凭证和表格数据都由你自己管理。

## 最终你会得到什么

- 一个你自己的 Worker HTTPS 地址，例如 `https://screenshot-bookkeeping-api.<name>.workers.dev`。
- 一个只有你知道的一次性邀请码，用于在 App 中创建第一个账号。
- 一个已绑定你的 Worker 地址的 APK。
- 一个由你的飞书自建应用读写的多维表格。

## 0. 准备环境

需要：

- macOS、Windows 或 Linux 电脑；
- Git、Node.js 20 或更高版本、npm；
- Android Studio（用于构建 APK）；
- Cloudflare 账号；
- 阿里云百炼账号及 API Key；
- 飞书企业账号，并且有权创建企业自建应用。

克隆仓库：

```bash
git clone https://github.com/ymhuangselina-wq/screenshot-bookkeeping-app.git
cd screenshot-bookkeeping-app
```

## 1. 创建飞书自建应用

1. 打开[https://open.feishu.cn/app](https://open.feishu.cn/app)，创建“企业自建应用”。
2. 在“凭证与基础信息”中记下 `App ID` 和 `App Secret`。`App Secret` 不要发给他人，也不要写入 Git 文件。
3. 在“权限管理”中开通用户身份的多维表格读写权限：
   - **查看、评论、编辑和管理多维表格** (`bitable:app`)；
   - 如果你要粘贴知识库中的多维表格链接，再开通知识库节点读取权限。首次部署建议先用 `/base/` 直接链接。
4. 创建并发布一个应用版本。如果企业开启了管理员审批，需先完成审批，新权限才会生效。
5. 在飞书中创建或打开要使用的多维表格，打开协作者/权限设置，把这个自建应用添加为可编辑协作者。

如果表格开启了高级权限，还要在高级权限中把包含该应用的组设为可读写，否则飞书会返回 403。

## 2. 准备百炼 API Key

在阿里云百炼控制台创建 API Key。本项目默认调用视觉模型 `qwen-vl-max`，会产生模型调用费用。请给账号设置余额提醒或用量预警。

## 3. 创建 Cloudflare 资源

```bash
cd worker
npm install
npx wrangler login
npx wrangler kv namespace create IDEMPOTENCY
npx wrangler d1 create screenshot-bookkeeping
```

两条创建命令会分别输出 KV `id` 和 D1 `database_id`。复制配置模板：

```bash
cp wrangler.toml.example wrangler.toml
```

打开 `worker/wrangler.toml`，把：

- `YOUR_KV_NAMESPACE_ID` 替换为 KV `id`；
- `YOUR_D1_DATABASE_ID` 替换为 D1 `database_id`；
- `YOUR_WORKER_DOMAIN` 暂时可以保留；本教程的单人本地账号流程不使用飞书 OAuth 回调。

应用数据库迁移：

```bash
npx wrangler d1 migrations apply screenshot-bookkeeping --remote
```

确认三个 migration 都显示已成功应用。

## 4. 写入 Worker 密钥

先在终端生成两个随机密钥，并存到安全的密码管理器中：

```bash
openssl rand -base64 32
openssl rand -base64 32
```

- 第一个作为 `PERSONAL_ACCESS_KEY`，它可以生成你的一次性注册邀请码。
- 第二个作为 `TOKEN_ENCRYPTION_KEY`，部署后不要随意更换。

依次执行以下命令。Wrangler 会在终端中让你粘贴值，输入时不会回显：

```bash
npx wrangler secret put DASHSCOPE_API_KEY
npx wrangler secret put FEISHU_APP_ID
npx wrangler secret put FEISHU_APP_SECRET
npx wrangler secret put PERSONAL_ACCESS_KEY
npx wrangler secret put TOKEN_ENCRYPTION_KEY
```

`FEISHU_APP_ID` 和 `FEISHU_APP_SECRET` 填第 1 步的飞书应用凭证。单人自部署不需要配置 `FEISHU_OAUTH_APP_ID` 和 `FEISHU_OAUTH_APP_SECRET`。

## 5. 部署并验证 Worker

```bash
npm run typecheck
npm test
npm run deploy
```

记下部署结果中的 HTTPS 地址，然后检查健康接口：

```bash
curl https://YOUR_WORKER_DOMAIN/health
```

预期返回包含 `"ok":true` 的 JSON。

## 6. 生成一次性邀请码

把下面的 Worker 地址和 `PERSONAL_ACCESS_KEY` 替换为你自己的值：

```bash
curl -X POST 'https://YOUR_WORKER_DOMAIN/v2/admin/invites' \
  -H 'Authorization: Bearer YOUR_PERSONAL_ACCESS_KEY' \
  -H 'Content-Type: application/json' \
  --data '{"expiresInDays":7}'
```

返回值中的 `code` 就是一次性邀请码。它只能使用一次，不要把它发到公开群或写入仓库。

## 7. 构建 Android APK

把 Worker 地址写入本地配置（这个文件已被 Git 忽略）：

```properties
# android-app/local.properties
serviceUrl=https://YOUR_WORKER_DOMAIN
```

然后用 Android Studio 打开 `android-app/`，等待 Gradle 同步完成，或直接在终端构建：

```bash
cd ../android-app
./gradlew testDebugUnitTest assembleDebug
```

macOS 如果终端找不到 Java，可使用 Android Studio 自带的 JDK：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug
```

APK 位于：

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

把 APK 传到你的 Android 8.0 或更高版本手机安装。这是调试签名包，只适合自用和测试；后续正式发布应使用自己保管的固定签名证书。

## 8. 首次使用

1. 打开 App，点“开始使用”。
2. 点“有邀请码，创建账号”。
3. 设置一个 3–40 位账号和至少 10 位密码，填入第 6 步生成的邀请码。当前没有自助找回密码，请妥善保存。
4. 选“连接已有多维表格”，在飞书中打开具体数据表，复制包含 `table=tbl...` 的完整链接。
5. 粘贴链接，检查字段映射，再确认连接。App 可以在你确认后补齐缺少的字段，不会删除已有记录。
6. 先做一条手工录入并保存，到飞书中确认记录已写入；然后再测试分享截图和 AI 识别。

## 验收清单

- [ ] `/health` 返回 `ok: true`。
- [ ] App 能用邀请码注册并再次登录。
- [ ] App 能读取多维表格名称和字段。
- [ ] 手工录入能写入飞书。
- [ ] 分享一张截图后能返回识别结果。
- [ ] Git 中没有 `.dev.vars`、`wrangler.toml`、`local.properties`、APK 或签名证书。

## 常见问题

### `/health` 正常，App 却连不上

检查构建 APK 时的 `serviceUrl` 是否为完整 HTTPS 地址，且末尾没有重复路径。修改后要重新构建并安装 APK。

### 提示“邀请码无效”

邀请码只能用一次且会过期。重新执行第 6 步生成新码。

### 提示飞书 403 或“无权访问”

依次检查：应用权限是否已申请、应用版本是否已发布/审批、应用是否已被添加为该表格的可编辑协作者、表格高级权限是否允许该应用读写。

### 多维表格链接无法识别

请打开具体数据表后再复制链接。首次测试优先用 `/base/` 链接，并确保链接中含有 `table=tbl...`。

### AI 识别返回 401/429 或超时

检查百炼 API Key、账号余额、模型开通状态和 Worker 日志。即使 AI 暂时不可用，仍可先用手工录入验证飞书写入链路。

## 升级与回退

1. 升级前先记录当前 Git tag 或 commit，并备份 `wrangler.toml`、签名证书和密钥（不要提交到 Git）。
2. 拉取新代码后，先执行 D1 migrations，再部署 Worker，最后用同一套签名证书构建新 APK。
3. 发布版本建议用 GitHub Releases 保存 APK 和更新说明；详见 [`RELEASE_PROCESS.md`](RELEASE_PROCESS.md)。
4. 如果新版有问题，可 checkout 上一个 tag 重新部署和构建。D1 migration 可能不可逆，涉及数据结构的升级要先备份数据库。

## 安全边界

- APK 内只包含 Worker 地址，不应包含百炼 Key、飞书 App Secret 或 Worker 管理密钥。
- Worker 会把截图内容发给百炼进行识别；确认后的字段会写入飞书。请根据你的数据敏感程度评估是否使用。
- 飞书应用只应获得必要权限，并只加入需要写入的多维表格。
- 如果怀疑密钥泄露，立即在对应平台重置，再用 `wrangler secret put` 更新 Worker。

