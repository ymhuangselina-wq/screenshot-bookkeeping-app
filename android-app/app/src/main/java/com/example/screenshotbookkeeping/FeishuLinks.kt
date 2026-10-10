package com.example.screenshotbookkeeping

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object FeishuLinks {
    fun open(context: Context, url: String) {
        val parsed = Uri.parse(url)
        val host = parsed.host.orEmpty()
        if (host != "feishu.cn" && !host.endsWith(".feishu.cn") && !host.endsWith(".larksuite.com")) return
        val appLink = Uri.parse("https://applink.feishu.cn/client/web_url/open")
            .buildUpon().appendQueryParameter("url", url).build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, appLink).setPackage("com.ss.android.lark"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "未找到飞书客户端，将在浏览器中打开", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Intent.ACTION_VIEW, parsed))
        }
    }
}
