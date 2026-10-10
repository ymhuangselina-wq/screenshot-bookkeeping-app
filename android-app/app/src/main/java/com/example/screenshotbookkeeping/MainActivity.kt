package com.example.screenshotbookkeeping

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        PaymentPageAccessibilityService.hideForAppForeground()
        if (PaymentNotificationBridge.listenerEnabled(this)) {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, PaymentNotificationListener::class.java)
            )
        }
    }
    private var pendingOAuth by mutableStateOf<Uri?>(null)
    private var pendingUri by mutableStateOf<Uri?>(null)
    private var pendingPayment by mutableStateOf<PaymentNotice?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (PaymentNotificationBridge.listenerEnabled(this)) {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, PaymentNotificationListener::class.java)
            )
        }
        pendingOAuth = oauthLink(intent)
        pendingUri = sharedImage(intent)
        pendingPayment = paymentNotice(intent)
        setContent {
            if (BuildConfig.PERSONAL_MODE) {
                val vm: MainViewModel = viewModel()
                ScreenshotBookkeepingApp(vm)
                val ledger by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(pendingUri, ledger.busy, ledger.config) {
                    if (!ledger.busy && ledger.config != null) pendingUri?.let { uri -> vm.acceptSharedImage(uri); pendingUri = null }
                }
                LaunchedEffect(pendingPayment, ledger.busy, ledger.config) {
                    if (!ledger.busy && ledger.config != null) pendingPayment?.let { notice -> vm.acceptPaymentNotification(notice); pendingPayment = null }
                }
                return@setContent
            }
            val auth: MultiplayerViewModel = viewModel()
            val account by auth.state.collectAsStateWithLifecycle()
            LaunchedEffect(pendingOAuth, account.busy) {
                if (!account.busy) pendingOAuth?.let { uri ->
                    val code = uri.getQueryParameter("code")
                    if (code != null) auth.completeOAuth(code,uri.getQueryParameter("appState").orEmpty())
                    else auth.oauthError(uri.getQueryParameter("error") ?: "授权未完成，请重试")
                    pendingOAuth = null
                }
            }
            if (account.me?.book != null && account.screen == AppScreen.LEDGER) {
                key(account.generation) {
                    val vm: MainViewModel = viewModel(key = "ledger:${account.generation}")
                    ScreenshotBookkeepingApp(vm) { vm.closeSettings(); auth.showSettings() }
                    val ledger by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { if (!vm.state.value.busy) vm.refreshConfig() }
                    LaunchedEffect(ledger.loginRequired) { if (ledger.loginRequired) auth.requireLogin() }
                    LaunchedEffect(pendingUri, ledger.busy, ledger.config) {
                        if (!ledger.busy && ledger.config != null) pendingUri?.let { uri -> vm.acceptSharedImage(uri); pendingUri = null }
                    }
                    LaunchedEffect(pendingPayment, ledger.busy, ledger.config) {
                        if (!ledger.busy && ledger.config != null) pendingPayment?.let { notice -> vm.acceptPaymentNotification(notice); pendingPayment = null }
                    }
                }
            } else MultiplayerApp(auth)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingOAuth = oauthLink(intent)
        pendingUri = sharedImage(intent)
        pendingPayment = paymentNotice(intent)
        setIntent(intent)
    }

    private fun oauthLink(intent: Intent): Uri? = intent.data?.takeIf {
        intent.action == Intent.ACTION_VIEW && it.scheme == "screenshotbookkeeping" && it.host == "oauth"
    }

    private fun sharedImage(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        if (intent.type?.startsWith("image/") == false) return null

        // Android vendors do not all put a shared screenshot in the same place.
        // Check the conventional EXTRA_STREAM first, then ClipData and data.
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { return it }
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()?.let { return it }
        intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }
        return intent.data
    }

    private fun paymentNotice(intent: Intent): PaymentNotice? {
        val amount = intent.getStringExtra(PaymentNotificationBridge.EXTRA_AMOUNT)?.takeIf(String::isNotBlank) ?: return null
        return PaymentNotice(
            amount = amount,
            merchant = intent.getStringExtra(PaymentNotificationBridge.EXTRA_MERCHANT).orEmpty().ifBlank { "支付消费" },
            platform = intent.getStringExtra(PaymentNotificationBridge.EXTRA_PLATFORM).orEmpty(),
            sourceText = intent.getStringExtra(PaymentNotificationBridge.EXTRA_SOURCE).orEmpty()
        )
    }
}
