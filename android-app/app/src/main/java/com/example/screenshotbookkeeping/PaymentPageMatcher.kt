package com.example.screenshotbookkeeping

internal object PaymentPageMatcher {
    fun isDetailPage(text: String): Boolean {
        // Message feeds contain payment words and amounts for multiple records.
        // They are not safe single-record recognition entry points.
        if (listOf("支付消息", "服务消息", "账单列表", "订单列表").any(text::contains)) return false
        return listOf("账单详情", "交易详情", "订单详情", "支付详情", "转账详情",
            "支付成功", "付款成功", "支付结果", "转账成功").any(text::contains)
    }
}
