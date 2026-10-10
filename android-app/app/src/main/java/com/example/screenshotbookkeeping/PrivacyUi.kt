package com.example.screenshotbookkeeping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val InvitePrivacyText = """截图记账 · 邀请测试隐私说明与使用条款

账号：后台保存账号名、密码校验摘要、登录会话、账本配置和识别额度。密码不会按明文保存。

飞书：账目写入你连接的飞书多维表格。后台为执行读写操作保存相应的加密应用凭证。仅连接你有权管理的表格，并在飞书中为应用授予必要权限。不要提供他人或公司禁止外部使用的凭证。

截图与识别：只有在你选择图片、分享截图或点击自动记账按钮后，App 才会将该图片经记账后台发送给阿里云百炼识别。后台不把原始截图写入数据库或账本附件。识别可能出错，请确认金额和字段后保存。

可选权限：支付通知提醒需要通知访问权限；页面智能监测需要无障碍权限，页面截屏还需系统授权。这些功能由你在设置中主动开启，可随时在系统设置中关闭。支付通知信息可能被填入记账表单，确认保存后写入飞书。

保留与删除：账号、应用凭证及账本配置保留至你注销账号；登录会话最长 30 天，记账请求去重信息最长 30 天。注销删除后台账号关联数据，不删除你飞书中的表格和已有账目，也不会代替你在飞书中撤销文档应用权限。

邀请测试：每日 AI 识别额度由服务控制，超额后仍可手工记账。测试服务可能中断；重要账目请在自己的飞书空间保留备份。本应用提供记账录入辅助，不能保证 AI 识别完全准确。

如需帮助，请联系提供邀请码的人。公开发布前将补充运营者身份和正式联系方式。
"""

@Composable
internal fun PrivacyLink() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("隐私说明与条款") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("隐私说明与条款") },
        text = { Text(InvitePrivacyText, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("我已阅读") } }
    )
}
