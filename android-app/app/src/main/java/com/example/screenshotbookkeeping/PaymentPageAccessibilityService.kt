package com.example.screenshotbookkeeping

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class PaymentPageAccessibilityService : AccessibilityService() {
    private var overlay: TextView? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayRetryAfter = 0L
    private var visiblePackage: String? = null
    private var lastDecision: String? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val monitorThread = android.os.HandlerThread("PaymentPageMonitor").apply { start() }
    private val monitorHandler = android.os.Handler(monitorThread.looper)
    @Volatile private var capturing = false
    private var captureStartedAt = 0L
    private var lastMonitorLog = 0L
    @Volatile private var mainProbePending = false
    private var mainProbeStarted = 0L
    private val reconcilePage = object : Runnable {
        override fun run() {
            try {
                val tick = android.os.SystemClock.elapsedRealtime()
                if (capturing && tick - captureStartedAt > 10000) {
                    capturing = false
                    android.util.Log.e("BookkeepingServices", "capture state timed out; monitoring resumed")
                }
                if (BuildConfig.DEBUG && tick - lastMonitorLog > 5000) {
                    android.util.Log.i("BookkeepingServices", "monitor heartbeat capturing=$capturing overlay=${overlay != null} attached=${overlay?.isAttachedToWindow}")
                    if (mainProbePending && tick - mainProbeStarted > 8000) {
                        android.util.Log.w("BookkeepingServices", "main thread delayed: " +
                            android.os.Looper.getMainLooper().thread.stackTrace.take(8).joinToString(" | "))
                    }
                    if (!mainProbePending) {
                        mainProbePending = true
                        mainProbeStarted = tick
                        mainHandler.post { mainProbePending = false }
                    }
                    lastMonitorLog = tick
                }
                if (!capturing) inspectActivePage()
            } catch (error: Exception) {
                android.util.Log.e("BookkeepingServices", "page reconciliation failed", error)
            } finally {
                monitorHandler.postDelayed(this, 800)
            }
        }
    }

    override fun onServiceConnected() {
        activeService = this
        // Polling with a stale accessibility cache can keep returning SystemUI
        // after switching back to a payment app. Window changes must invalidate
        // our view of the foreground; use fresh nodes on supported Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setCacheEnabled(false)
        if (BuildConfig.DEBUG) android.util.Log.i("BookkeepingServices", "page-monitor connected")
        monitorHandler.removeCallbacks(reconcilePage)
        monitorHandler.post(reconcilePage)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The overlay itself emits events with our package name. They do not
        // mean MainActivity is foreground; reacting to them hides and then
        // re-shows the same overlay indefinitely.
        val eventPackage = event?.packageName?.toString() ?: return
        if (eventPackage == this.packageName || capturing) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackage !in supportedPagePackages && eventPackage != "com.android.systemui") {
            hideOverlay()
        }
        // Do not recursively scan on every content/overlay event. The periodic
        // reconciliation already checks fresh windows and coalesces bursts.
    }

    private fun inspectActivePage() {
        val applicationWindow = windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused)
        }
        val root = applicationWindow?.root ?: rootInActiveWindow?.takeUnless {
            // A floating accessibility window can temporarily become the
            // active root. Never use that as the foreground application.
            it.packageName?.toString() == this.packageName && overlay?.visibility == android.view.View.VISIBLE
        }
        if (root == null) {
            if (lastDecision != "no-root") android.util.Log.i("BookkeepingServices", "monitor no readable root")
            lastDecision = "no-root"
            hideOverlay()
            return
        }
        val packageName = root.packageName?.toString().orEmpty()
        val tick = android.os.SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG && tick - lastMonitorLog > 5000) {
            android.util.Log.i("BookkeepingServices", "monitor root=$packageName capturing=$capturing overlay=${overlay != null} attached=${overlay?.isAttachedToWindow}")
            lastMonitorLog = tick
        }
        if (packageName == this.packageName || packageName !in supportedPagePackages) {
            hideOverlay()
            return
        }
        val text = collectText(root).take(5000)
        val shouldShow = PaymentPageMatcher.isDetailPage(text)
        val decision = "$packageName readable=${text.isNotBlank()} matched=$shouldShow"
        if (BuildConfig.DEBUG && decision != lastDecision) {
            android.util.Log.i("BookkeepingServices", decision)
            lastDecision = decision
        }
        if (shouldShow) showOverlay(packageName) else hideOverlay()
    }

    override fun onInterrupt() = hideOverlay()

    override fun onDestroy() {
        monitorHandler.removeCallbacks(reconcilePage)
        monitorThread.quitSafely()
        overlay?.let { runCatching { overlayWindowManager?.removeViewImmediate(it) } }
        overlay = null
        overlayWindowManager = null
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun showOverlay(packageName: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { showOverlay(packageName) }
            return
        }
        if (capturing) return
        visiblePackage = packageName
        if (overlay?.isAttachedToWindow == true) {
            if (overlay?.visibility != android.view.View.VISIBLE) {
                overlay?.visibility = android.view.View.VISIBLE
                android.util.Log.i("BookkeepingServices", "overlay restored for $packageName")
            }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < overlayRetryAfter) return
        overlay = null
        // A reconnected accessibility service can retain a WindowManager with
        // an expired overlay token. A fresh display context obtains the token
        // for the current accessibility connection instead of reusing it.
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val overlayContext = createDisplayContext(display)
        val windowManager = overlayContext.getSystemService(WindowManager::class.java)
        val button = TextView(overlayContext).apply {
            text = "截图记账"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.rgb(244, 123, 32))
            }
            setOnClickListener { captureAndOpenApp() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = dp(16)
            y = dp(96)
        }
        runCatching { windowManager.addView(button, params) }
            .onSuccess {
                overlay = button
                overlayWindowManager = windowManager
                overlayRetryAfter = 0L
                android.util.Log.i("BookkeepingServices", "overlay added")
            }
            .onFailure {
                overlayRetryAfter = android.os.SystemClock.elapsedRealtime() + 3000
                android.util.Log.e("BookkeepingServices", "overlay add failed", it)
            }
    }

    private fun hideOverlay() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { hideOverlay() }
            return
        }
        // Keep the window attached. Clicking or switching apps only changes
        // visibility, avoiding repeated removal/addition of the overlay token.
        if (overlay?.visibility != android.view.View.GONE) {
            overlay?.visibility = android.view.View.GONE
            android.util.Log.i("BookkeepingServices", "overlay hidden")
        }
        visiblePackage = null
    }

    private fun captureAndOpenApp() {
        if (capturing) return
        android.util.Log.i("BookkeepingServices", "capture requested")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "当前系统版本不支持直接截屏，请手动截图后分享", Toast.LENGTH_LONG).show()
            return
        }
        hideOverlay()
        capturing = true
        captureStartedAt = android.os.SystemClock.elapsedRealtime()
        try {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                android.util.Log.i("BookkeepingServices", "capture succeeded")
                val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bitmap == null) {
                    capturing = false
                    showCaptureError()
                    return
                }
                runCatching { saveAndOpen(bitmap) }.onFailure {
                    android.util.Log.e("BookkeepingServices", "capture handoff failed", it)
                    showCaptureError()
                }
                capturing = false
            }

            override fun onFailure(errorCode: Int) {
                capturing = false
                android.util.Log.e("BookkeepingServices", "capture failed code=$errorCode")
                showCaptureError()
            }
        })
        } catch (error: Exception) {
            capturing = false
            android.util.Log.e("BookkeepingServices", "capture request failed", error)
            showCaptureError()
        }
    }

    private fun saveAndOpen(bitmap: Bitmap) {
        val directory = File(cacheDir, "payment-captures").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach(File::delete)
        val file = File(directory, "payment-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "支付页截图", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
        android.util.Log.i("BookkeepingServices", "capture handed to app")
    }

    private fun showCaptureError() {
        Toast.makeText(this, "该页面暂时无法截取，请手动截图后分享到截图记账", Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun collectText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val parts = ArrayList<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || parts.size >= 240 || visited >= 300) return
            visited++
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return parts.distinct().joinToString(" ")
    }

    companion object {
        private var activeService: PaymentPageAccessibilityService? = null
        fun hideForAppForeground() {
            activeService?.hideOverlay()
        }
        private val supportedPagePackages = setOf(
            "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay", "com.icbc",
            "com.chinamworld.main", "com.android.bankabc", "com.chinamworld.bocmbci", "cmb.pb",
            "cn.com.cmbc.newmbank", "com.bankcomm.Bankcomm", "com.ecitic.bank.mobile",
            "cn.com.spdb.mobilebank.per", "com.pingan.paces.ccms", "com.yitong.mbank.psbc"
        )
        private val paymentPageWords = listOf(
            "支付成功", "付款成功", "交易成功", "转账成功", "支付结果",
            "订单详情", "账单详情", "交易详情", "收银台", "支付详情", "转账详情",
            "微信支付", "已支付", "实付款", "实付金额", "付款金额", "交易记录"
        )

        fun enabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val component = ComponentName(context, PaymentPageAccessibilityService::class.java)
            return enabled.split(':').any { value ->
                value.equals(component.flattenToString(), ignoreCase = true) ||
                    value.equals(component.flattenToShortString(), ignoreCase = true) ||
                    ComponentName.unflattenFromString(value) == component
            }
        }
    }
}
