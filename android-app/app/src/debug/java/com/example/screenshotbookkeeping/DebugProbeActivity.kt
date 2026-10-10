package com.example.screenshotbookkeeping

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.util.Base64
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Debug-only probe that exercises account-safe API paths without exposing secrets or ledger data. */
class DebugProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("notificationTest", false)) {
            PaymentNotificationBridge.showTestPrompt(this)
            Log.i(TAG, "test-prompt posted permitted=${PaymentNotificationBridge.promptsEnabled(this)}")
            finish()
            return
        }
        setContentView(TextView(this).apply { text = "Running bookkeeping API diagnostics…" })
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val settings = SecureSettings(applicationContext)
            val api = ApiClient(settings)
            suspend fun <T> timed(name: String, block: suspend () -> T): T {
                val started = System.currentTimeMillis()
                return try {
                    withTimeout(75_000) { block() }.also { Log.i(TAG, "$name ok ${System.currentTimeMillis() - started}ms") }
                } catch (error: Throwable) {
                    Log.e(TAG, "$name failed ${System.currentTimeMillis() - started}ms: ${error.javaClass.simpleName}: ${error.message}")
                    throw error
                }
            }
            withContext(Dispatchers.IO) {
                try {
                    val health = timed("health") { api.health() }
                    Log.i(TAG, "server-build=${health.build}")
                    if (health.build == "20261005.1-explicit-book-reliability") {
                        try {
                            api.save(RecordRequest(java.util.UUID.randomUUID().toString(), "", "", 0.0, "", emptyList(), null, null,
                                mapOf("__diagnostic_unknown_field__" to "must-not-save")))
                            Log.e(TAG, "save-validation unexpectedly accepted an unknown field")
                        } catch (e: IllegalStateException) {
                            Log.i(TAG, "save-validation ${e.message}")
                        }
                    }
                    val books = timed("books") { api.books() }
                    val config = timed("config") { api.config() }
                    Log.i(TAG, "config-fields=${config.fields.size} types=${config.fields.map { it.fieldType }}")
                    val parsed = timed("parse-synthetic") { api.parse(ParseRequest(syntheticPaymentImage(), MainViewModel.now())) }
                    Log.i(TAG, "parse supported=${parsed.supported} values=${parsed.values.size} knownIds=${parsed.values.keys.all { id -> config.fields.any { it.fieldId == id } }} warnings=${parsed.warnings} suggestions=${parsed.suggestedOptions.size}")
                    if (parsed.supported && parsed.values.isEmpty()) {
                        val fresh = timed("refresh-empty-result") { api.refreshCurrentPersonalBook() }
                        val retry = timed("retry-empty-result") { api.parse(ParseRequest(syntheticPaymentImage(), MainViewModel.now())) }
                        val currentFields = fresh.books.firstOrNull { it.id == settings.currentBookId }?.fields.orEmpty()
                        Log.i(TAG, "retry-empty supported=${retry.supported} values=${retry.values.size} knownIds=${retry.values.keys.all { id -> currentFields.any { it.fieldId == id } }}")
                    }
                    val original = settings.currentBookId.ifBlank { books.currentBookId.orEmpty() }
                    val alternate = books.books.firstOrNull { it.id != original }?.id
                    if (alternate != null) {
                        try {
                            timed("switch-alternate") { api.switchBook(alternate) }
                            val alternateConfig = timed("config-after-switch") { api.config() }
                            val reparsed = timed("reparse-after-switch") { api.parse(ParseRequest(syntheticPaymentImage(), MainViewModel.now())) }
                            Log.i(TAG, "reparse supported=${reparsed.supported} values=${reparsed.values.size} knownIds=${reparsed.values.keys.all { id -> alternateConfig.fields.any { it.fieldId == id } }}")
                        } finally {
                            if (original.isNotBlank()) timed("switch-restore") { api.switchBook(original) }
                        }
                        timed("refresh-current") { api.refreshCurrentPersonalBook() }
                    } else Log.i(TAG, "switch skipped: no alternate book")
                } catch (_: Throwable) { }
            }
            finish()
        }
    }

    private fun syntheticPaymentImage(): String {
        val bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
        listOf("支付成功", "实付金额 ¥20.00", "支付方式 支付宝", "2026-09-20 12:00:00", "商户：测试餐厅")
            .forEachIndexed { index, line -> canvas.drawText(line, 60f, 160f + index * 120f, paint) }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object { private const val TAG = "BookkeepingProbe" }
}
