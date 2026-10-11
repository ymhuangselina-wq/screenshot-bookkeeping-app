package com.example.screenshotbookkeeping

import org.junit.Assert.assertEquals
import org.junit.Test

class OptionOrderingTest {
    @Test
    fun newlySelectedOptionMovesToFront() {
        assertEquals("测试333、餐饮、交通", promoteSelectedOption("测试333", "餐饮、交通"))
    }

    @Test
    fun existingOptionMovesToFrontWithoutDuplication() {
        assertEquals("交通、餐饮、购物", promoteSelectedOption("交通", "餐饮、交通、购物"))
    }

    @Test
    fun renamedOptionMovesToFront() {
        assertEquals(
            listOf("打车", "餐饮", "购物"),
            renameOptionToFront(listOf("餐饮", "交通", "购物"), "交通", "打车")
        )
    }

    @Test
    fun renamedOptionDoesNotCreateDuplicate() {
        assertEquals(
            listOf("打车", "餐饮", "购物"),
            renameOptionToFront(listOf("餐饮", "交通", "打车", "购物"), "交通", "打车")
        )
    }
}
