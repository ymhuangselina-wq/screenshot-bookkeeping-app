package com.example.screenshotbookkeeping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpErrorMessageTest {
    @Test fun translatesAliyunDebtResponseAndNamesOperation() {
        assertEquals(
            "加载账本失败：阿里云账户欠费，服务已暂停。充值后请等待几分钟再重试。（HTTP 403）",
            httpErrorMessage(403, "current user is in debt", "/books")
        )
    }

    @Test fun preservesStructuredServerMessage() {
        val result = httpErrorMessage(401, """{"error":{"code":"unauthorized","message":"访问密钥无效"}}""", "/config")
        assertTrue(result.contains("加载服务配置失败"))
        assertTrue(result.contains("个人访问密钥无效"))
    }
}
