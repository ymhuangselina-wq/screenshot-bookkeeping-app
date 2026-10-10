package com.example.screenshotbookkeeping

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSerializationTest {
    @Test fun defaultDateIsInSubmittedValuesAndRecognitionCanOverrideIt() {
        val field = DynamicFieldDto(fieldId = "fldDate", displayName = "记录日期", fieldType = 5)
        val config = AppConfig(emptyList(), emptyList(), emptyList(), fields = listOf(field))
        val form = emptyDynamicForm(config, "2026-10-05 12:00:00")
        assertEquals("2026-10-05 12:00:00", form.dynamicValues["fldDate"])
        val recognized = form.copy(dynamicValues = form.dynamicValues + mapOf("fldDate" to "2026-10-02 20:50:05"))
        assertEquals("2026-10-02 20:50:05", recognized.dynamicValues["fldDate"])
    }
    @Test fun dynamicRequestIncludesActualFieldIdsAndDate() {
        val values = mapOf("fldName" to "晚餐", "fldAmount" to "160.855", "fldDate" to "2026-10-02 20:50:05")
        val input = RecordRequest("request", "", "", 0.0, "", emptyList(), null, null, values)
        val encoded = Json.parseToJsonElement(Json.encodeToString(input)).jsonObject["values"]!!.jsonObject
        assertEquals("晚餐", encoded["fldName"]!!.jsonPrimitive.content)
        assertEquals("2026-10-02 20:50:05", encoded["fldDate"]!!.jsonPrimitive.content)
        assertEquals("160.855", encoded["fldAmount"]!!.jsonPrimitive.content)
    }
}
