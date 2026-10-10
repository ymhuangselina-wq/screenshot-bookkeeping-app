package com.example.screenshotbookkeeping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentNoticeTest {
    @Test fun recognizesUnionPayAndTransferAndBankText() {
        assertEquals("59.90", PaymentNotificationBridge.parseContent("com.unionpay", "云闪付", "支付成功，金额59.90元")?.amount)
        assertEquals("20.00", PaymentNotificationBridge.parseContent("com.tencent.mm", "微信支付", "转账成功 ￥20.00")?.amount)
        assertEquals("100", PaymentNotificationBridge.parseContent("com.bank.other", "银行", "尾号1234消费人民币100元")?.amount)
    }
    @Test fun supportsInboxLinesWithoutMainText() {
        assertEquals("35", PaymentNotificationBridge.parseContent("com.unionpay", "云闪付", "", lines = listOf("付款成功 35元"))?.amount)
    }
    @Test fun rejectsRefundsAndUnrelatedMessages() {
        assertNull(PaymentNotificationBridge.parseContent("com.tencent.mm", "微信支付", "退款成功 ￥25.00"))
        assertNull(PaymentNotificationBridge.parseContent("com.random.chat", "朋友", "晚餐25元"))
    }
}
