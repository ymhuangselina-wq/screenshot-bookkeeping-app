package com.example.screenshotbookkeeping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPageMatcherTest {
    @Test fun paymentMessageFeedIsNotDetail() {
        assertFalse(PaymentPageMatcher.isDetailPage("服务消息 支付消息 转账成功 查看详情"))
    }
    @Test fun detailIsSupported() {
        assertTrue(PaymentPageMatcher.isDetailPage("账单详情 交易成功 实付金额"))
    }
    @Test fun genericPaymentOrListIsNotDetail() {
        assertFalse(PaymentPageMatcher.isDetailPage("微信支付 交易记录 查看详情"))
        assertFalse(PaymentPageMatcher.isDetailPage("订单列表 支付成功"))
    }
}
