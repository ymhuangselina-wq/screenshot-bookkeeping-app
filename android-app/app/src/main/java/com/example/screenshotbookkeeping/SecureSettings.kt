package com.example.screenshotbookkeeping

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal fun normalizeServiceUrl(value: String): String = value
    .trim()
    .trimEnd('/')
    .removeSuffix("/health")
    .trimEnd('/')

class SecureSettings(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var workerUrl: String
        get() = normalizeServiceUrl(preferences.getString("worker_url", "") ?: "")
        set(value) = preferences.edit().putString("worker_url", normalizeServiceUrl(value)).apply()
    var accessKey: String
        get() = preferences.getString("access_key", "") ?: ""
        set(value) = preferences.edit().putString("access_key", value.trim()).apply()
    var sessionToken: String
        get() = preferences.getString("session_token", "") ?: ""
        set(value) = preferences.edit().putString("session_token", value).apply()
    var sessionExpiresAt: Long
        get() = preferences.getLong("session_expires_at", 0L)
        set(value) = preferences.edit().putLong("session_expires_at", value).apply()
    fun clearSession() {
        val prefix = "account:$accountId"
        val editor = preferences.edit()
        preferences.all.keys.filter { it == "catalog:$prefix" || it == "current_book:$prefix" || it.startsWith("recent:$prefix:") }.forEach { editor.remove(it) }
        editor.remove("session_token").remove("session_expires_at").remove("account_id").apply()
    }
    var accountId: String
        get() = preferences.getString("account_id", "").orEmpty()
        set(value) = preferences.edit().putString("account_id", value).apply()
    var loginVerifier: String
        get() = preferences.getString("login_verifier", "").orEmpty()
        set(value) = preferences.edit().putString("login_verifier", value).apply()
    var loginState: String
        get() = preferences.getString("login_state", "").orEmpty()
        set(value) = preferences.edit().putString("login_state", value).apply()
    val isConfigured get() = if (BuildConfig.PERSONAL_MODE) workerUrl.startsWith("https://") && accessKey.isNotBlank() else sessionToken.isNotBlank() && accountId.isNotBlank()
    private val cacheScope get() = if (BuildConfig.PERSONAL_MODE) workerUrl else "account:$accountId"
    var cachedCatalog: String
        get() = preferences.getString("catalog:$cacheScope", "").orEmpty()
        set(value) = preferences.edit().putString("catalog:$cacheScope", value).apply()
    var currentBookId: String
        get() = preferences.getString("current_book:$cacheScope", "").orEmpty()
        set(value) = preferences.edit().putString("current_book:$cacheScope", value).apply()
    private fun recentKey(fieldId: String) = if (BuildConfig.PERSONAL_MODE) "recent:$currentBookId:$fieldId" else "recent:$cacheScope:$currentBookId:$fieldId"
    fun recentOptions(fieldId: String): List<String> = preferences.getString(recentKey(fieldId), "").orEmpty().split('\n').filter(String::isNotBlank)
    fun rememberOptions(fieldId: String, values: List<String>) {
        preferences.edit().putString(recentKey(fieldId), (values + recentOptions(fieldId)).distinct().take(30).joinToString("\n")).apply()
    }
}
