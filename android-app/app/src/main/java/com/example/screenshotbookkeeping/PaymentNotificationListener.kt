package com.example.screenshotbookkeeping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class PaymentNotice(
    val amount: String,
    val merchant: String,
    val platform: String,
    val sourceText: String
)

class PaymentNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        if (BuildConfig.DEBUG) android.util.Log.i("BookkeepingServices", "notification-listener connected")
    }
    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, PaymentNotificationListener::class.java))
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notice = PaymentNotificationBridge.parse(sbn) ?: return
        if (BuildConfig.DEBUG) android.util.Log.i("BookkeepingServices", "payment-notification matched source=${sbn.packageName}")
        PaymentNotificationBridge.showBookkeepingPrompt(this, notice, sbn.key.hashCode())
    }
}

object PaymentNotificationBridge {
    const val EXTRA_AMOUNT = "payment_notice_amount"
    const val EXTRA_MERCHANT = "payment_notice_merchant"
    const val EXTRA_PLATFORM = "payment_notice_platform"
    const val EXTRA_SOURCE = "payment_notice_source"

    private const val CHANNEL_ID = "payment_bookkeeping"
    private val supportedPackages = mapOf(
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信支付",
        "com.unionpay" to "云闪付",
        "com.icbc" to "工商银行",
        "com.chinamworld.main" to "建设银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "cmb.pb" to "招商银行",
        "cn.com.cmbc.newmbank" to "民生银行",
        "com.bankcomm.Bankcomm" to "交通银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "cn.com.spdb.mobilebank.per" to "浦发银行",
        "com.pingan.paces.ccms" to "平安银行",
        "com.yitong.mbank.psbc" to "邮储银行"
    )
    private val successWords = listOf("支付成功", "付款成功", "交易成功", "扣款成功", "转账成功", "转账已到账", "已转账", "转出", "消费", "支出")
    private val bankEvidenceWords = listOf("银行", "信用卡", "借记卡", "尾号", "卡号", "扣款", "消费", "转账")
    private val amountPatterns = listOf(
        Regex("(?:¥|￥|人民币|RMB|CNY)\\s*([0-9]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        Regex("(?:支付|付款|金额|扣款)[：:\\s]*([0-9]+(?:\\.[0-9]{1,2})?)\\s*元?"),
        Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")
    )

    fun parse(sbn: StatusBarNotification): PaymentNotice? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        return parseContent(sbn.packageName, title, text, bigText, lines)
    }

    internal fun parseContent(packageName: String, title: String, text: String, bigText: String = "", lines: List<String> = emptyList()): PaymentNotice? {
        val source = (listOf(title, text, bigText) + lines).filter(String::isNotBlank).distinct().joinToString(" ")
        if (listOf("退款成功", "收款成功", "收款到账").any(source::contains)) return null
        val platform = supportedPackages[packageName] ?: run {
            if (bankEvidenceWords.none(source::contains)) return null
            "银行卡"
        }
        if (successWords.none(source::contains)) return null
        val amount = amountPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(source)?.groupValues?.getOrNull(1)
        } ?: return null
        val merchant = listOf(title, text, bigText)
            .firstOrNull { value -> value.isNotBlank() && successWords.none(value::contains) && amountPatterns.none { it.containsMatchIn(value) } }
            ?.take(40)
            ?: if (source.contains("转账")) "转账" else "支付消费"
        return PaymentNotice(amount, merchant, platform, source)
    }

    fun listenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(context, PaymentNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun promptsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_HIGH) != NotificationManager.IMPORTANCE_NONE
    }

    fun showTestPrompt(context: Context) {
        showBookkeepingPrompt(
            context,
            PaymentNotice("25.50", "包点早餐", "支付宝", "支付成功 ￥25.50 包点早餐"),
            20260927
        )
    }

    fun showBookkeepingPrompt(context: Context, notice: PaymentNotice, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "支付完成记账", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "检测到支付成功后，提醒你确认并记账"
                }
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AMOUNT, notice.amount)
            putExtra(EXTRA_MERCHANT, notice.merchant)
            putExtra(EXTRA_PLATFORM, notice.platform)
            putExtra(EXTRA_SOURCE, notice.sourceText)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("刚完成一笔支付？")
            .setContentText("¥${notice.amount} · ${notice.merchant}，点击立即记账")
            .setStyle(Notification.BigTextStyle().bigText("已识别 ${notice.platform} 支付 ¥${notice.amount}。点击后会打开截图记账，请确认商户和账本后保存。"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(notificationId, notification)
    }
}
