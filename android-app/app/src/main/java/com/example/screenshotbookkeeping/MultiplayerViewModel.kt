package com.example.screenshotbookkeeping

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import java.security.SecureRandom
import java.security.MessageDigest
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

enum class AppScreen { WELCOME, LOGIN, BOOK_CHOICE, CONNECT, MAP_FIELDS, CREATE_BOOK, LEDGER, SETTINGS }

data class MappingDraft(
    val semanticKey: String,
    val enabled: Boolean,
    val required: Boolean,
    val displayName: String,
    val expectedType: Int,
    val fieldId: String? = null,
    val status: String = "missing",
    val optionsText: String = ""
) {
    fun toDto() = MappingInputDto(semanticKey, enabled, fieldId, displayName.trim(), optionsText.split("、", ",", "，").map(String::trim).filter(String::isNotBlank).distinct())
}

data class MultiplayerState(
    val generation: String = UUID.randomUUID().toString(),
    val screen: AppScreen = AppScreen.WELCOME,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val inviteCode: String = "",
    val username: String = "",
    val password: String = "",
    val registering: Boolean = false,
    val privacyAccepted: Boolean = false,
    val me: MeResponse? = null,
    val tableUrl: String = "",
    val inspection: InspectResponse? = null,
    val mappings: List<MappingDraft> = emptyList(),
    val newBookName: String = "我的记账本",
    val newTableName: String = "账目",
    val form: LedgerForm = LedgerForm(),
    val imageUri: Uri? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val saveSucceeded: Boolean = false,
    val deleteConfirm: Boolean = false
)

class MultiplayerViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SecureSettings(application)
    private val api = ApiClient(settings)
    private val _state = MutableStateFlow(MultiplayerState())
    val state = _state.asStateFlow()

    init { if (settings.sessionToken.isNotBlank()) loadMe() }

    fun reloadAccount() = loadMe()
    fun requireLogin() {
        settings.clearSession()
        _state.value = MultiplayerState(screen = AppScreen.LOGIN, message = "登录或飞书授权已过期，请重新登录")
    }
    fun backToWelcome() = set { copy(screen = AppScreen.WELCOME,error = null) }
    fun showLogin() = set { copy(screen = AppScreen.LOGIN, error = null) }
    fun setUsername(value: String) = set { copy(username = value,error = null) }
    fun setPassword(value: String) = set { copy(password = value,error = null) }
    fun setRegistering(value: Boolean) = set { copy(registering = value,password = "",error = null) }
    fun setPrivacyAccepted(value: Boolean) = set { copy(privacyAccepted = value) }
    fun signIn() = task {
        val current = _state.value
        require(current.privacyAccepted) { "请先阅读并同意隐私说明和使用条款" }
        val input = AccountCredentials(current.username.trim(),current.password,if (current.registering) current.inviteCode.trim() else null,deviceName = "${Build.MANUFACTURER} ${Build.MODEL}")
        val result = if (current.registering) api.registerAccount(input) else api.loginAccount(input)
        settings.clearSession()
        settings.sessionToken = result.sessionToken
        settings.sessionExpiresAt = result.expiresAt
        _state.value = _state.value.copy(password = "",generation = UUID.randomUUID().toString())
        loadMeInternal()
    }
    fun setInvite(value: String) = set { copy(inviteCode = value, error = null) }
    fun loginUrl(): String {
        fun random() = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        settings.loginVerifier = random()
        settings.loginState = random()
        val challenge = MessageDigest.getInstance("SHA-256").digest(settings.loginVerifier.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return api.authUrl(_state.value.inviteCode.trim(), "${Build.MANUFACTURER} ${Build.MODEL}",settings.loginState,challenge)
    }
    fun oauthError(message: String) = set { copy(screen = AppScreen.LOGIN, error = message) }

    fun completeOAuth(code: String, appState: String) = task {
        require(settings.loginState.isNotBlank() && appState == settings.loginState) { "此次登录不属于当前设备，请重新登录" }
        val session = api.exchange(code,settings.loginVerifier)
        settings.loginState = ""
        settings.loginVerifier = ""
        settings.sessionToken = session.sessionToken
        settings.sessionExpiresAt = session.expiresAt
        loadMeInternal()
        resumeAfterSetup()
    }

    private fun loadMe() = task {
        try { loadMeInternal(); resumeAfterSetup() }
        catch (e: Exception) { if (e is ApiFailure && e.status == 401) requireLogin(); throw e }
    }

    private suspend fun loadMeInternal() {
        if (settings.sessionExpiresAt in 1..(System.currentTimeMillis() + 7 * 86_400_000L)) {
            val refreshed = api.refreshSession()
            settings.sessionToken = refreshed.sessionToken
            settings.sessionExpiresAt = refreshed.expiresAt
        }
        val me = api.me()
        settings.accountId = me.user.id
        _state.value = _state.value.copy(me = me, screen = if (me.book == null) AppScreen.BOOK_CHOICE else AppScreen.LEDGER)
    }

    private fun resumeAfterSetup() {
        val current = _state.value
        if (current.me?.book == null) _state.value = current.copy(screen = AppScreen.BOOK_CHOICE)
        else if (current.imageUri != null) _state.value = current.copy(screen = AppScreen.LEDGER)
        else _state.value = current.copy(screen = AppScreen.LEDGER)
    }

    fun chooseConnect() = set { copy(screen = AppScreen.CONNECT, inspection = null, mappings = emptyList(), error = null) }
    fun chooseCreate() = set { copy(screen = AppScreen.CREATE_BOOK, mappings = defaultMappings(), error = null) }
    fun setTableUrl(value: String) = set { copy(tableUrl = value, error = null) }
    fun inspect(tableId: String? = null) = task {
        val result = api.inspectBook(_state.value.tableUrl.trim(), tableId)
        if (result.selectionRequired) {
            _state.value = _state.value.copy(inspection = result, message = "请选择要记账的数据表")
        } else {
            _state.value = _state.value.copy(inspection = result, mappings = draftsFrom(result), screen = AppScreen.MAP_FIELDS, message = null)
        }
    }

    fun updateMapping(index: Int, transform: (MappingDraft) -> MappingDraft) = set {
        copy(mappings = mappings.toMutableList().also { it[index] = transform(it[index]) }, error = null)
    }

    fun updateNewBookName(value: String) = set { copy(newBookName = value) }
    fun updateNewTableName(value: String) = set { copy(newTableName = value) }

    fun connectBook() = task {
        val inspect = _state.value.inspection ?: error("请先读取表格")
        val table = inspect.table ?: error("请选择数据表")
        validateMappings(_state.value.mappings)
        api.connectBook(ConnectBookRequest(inspect.appToken, table.table_id, inspect.sourceUrl, _state.value.mappings.map(MappingDraft::toDto)))
        loadMeInternal()
        _state.value = _state.value.copy(screen = AppScreen.LEDGER, message = "账本已连接")
        
    }

    fun createBook() = task {
        val current = _state.value
        require(current.newBookName.isNotBlank() && current.newTableName.isNotBlank()) { "请填写账本和数据表名称" }
        validateMappings(current.mappings)
        api.createBook(CreateBookRequest(UUID.randomUUID().toString(), current.newBookName.trim(), current.newTableName.trim(), current.mappings.map(MappingDraft::toDto)))
        loadMeInternal()
        _state.value = _state.value.copy(screen = AppScreen.LEDGER, message = "账本创建成功")
        
    }

    fun acceptSharedImage(uri: Uri) {
        _state.value = _state.value.copy(imageUri = uri, requestId = UUID.randomUUID().toString(), message = "已收到截图", error = null)
        when {
            settings.sessionToken.isBlank() -> _state.value = _state.value.copy(screen = AppScreen.LOGIN, message = "登录后将继续识别这张截图")
            _state.value.me?.book == null -> _state.value = _state.value.copy(screen = AppScreen.BOOK_CHOICE, message = "设置账本后将继续识别这张截图")
            else -> parse(uri)
        }
    }

    fun retryParse() = _state.value.imageUri?.let(::parse)
    private fun parseShortly(uri: Uri) { viewModelScope.launch { delay(50); parse(uri) } }
    private fun parse(uri: Uri) = task {
        val encoded = withContext(Dispatchers.Default) { ImageEncoder.toJpegDataUrl(getApplication(), uri) }
        val result = api.parseV2(ParseRequest(encoded, now()))
        val low = buildSet {
            if (result.date == null || result.confidence.date < .7) add("date")
            if (result.purpose == null || result.confidence.purpose < .7) add("purpose")
            if (result.amount == null || result.confidence.amount < .7) add("amount")
        }
        _state.value = _state.value.copy(
            screen = AppScreen.LEDGER,
            form = if (result.supported) LedgerForm(
                date = result.date ?: now(), purpose = result.purpose.orEmpty(), amount = result.amount?.let { "%.2f".format(it) }.orEmpty(),
                paymentPlatform = result.paymentPlatform.orEmpty(), tags = result.tags.toSet(), note = result.note.orEmpty(), project = result.project.orEmpty(),
                lowConfidence = low
            ) else LedgerForm(date = now(), lowConfidence = setOf("purpose", "amount")),
            me = _state.value.me?.copy(quota = _state.value.me!!.quota.copy(remaining = result.quotaRemaining)),
            message = if (result.supported) result.warnings.joinToString("；").ifBlank { "AI 已识别，请确认后保存" } else null,
            error = if (result.supported) null else "这不像单笔支付结果，请手工填写"
        )
    }

    fun newManual() = set { copy(screen = AppScreen.LEDGER, form = LedgerForm(date = now()), imageUri = null, requestId = UUID.randomUUID().toString(), message = null, error = null, saveSucceeded = false) }
    fun updateForm(value: LedgerForm) = set { copy(form = value, message = null) }
    fun save() {
        val current = _state.value
        val form = current.form
        val amount = form.amount.toDoubleOrNull()
        val enabled = current.me?.book?.mappings?.filter { it.enabled }?.map { it.semanticKey }?.toSet().orEmpty()
        val validation = when {
            form.lowConfidence.any { it == "date" || it == "purpose" || it == "amount" } -> "请点击并确认标红的日期、用途或金额"
            form.date.isBlank() || runCatching { OffsetDateTime.parse(form.date) }.isFailure -> "请输入有效日期时间"
            form.purpose.isBlank() -> "请输入用途"
            amount == null || amount <= 0 || abs(amount * 100 - round(amount * 100)) > .000001 -> "金额必须为正数且最多两位小数"
            "paymentPlatform" in enabled && form.paymentPlatform.isBlank() -> "请选择支付平台"
            else -> null
        }
        if (validation != null) { _state.value = current.copy(error = validation); return }
        task {
            api.saveV2(RecordRequest(current.requestId, form.date, form.purpose.trim(), amount!!, form.paymentPlatform, form.tags.toList(), form.note.ifBlank { null }, form.project.ifBlank { null }))
            _state.value = _state.value.copy(saveSucceeded = true, imageUri = null, message = "已保存到“${_state.value.me?.book?.name}”")
        }
    }

    fun showSettings() = task { loadMeInternal(); _state.value = _state.value.copy(screen = AppScreen.SETTINGS,error = null) }
    fun backToLedger() = set { copy(screen = if (me?.book == null) AppScreen.BOOK_CHOICE else AppScreen.LEDGER, error = null) }
    fun refreshBook() = task { api.refreshCurrentPersonalBook(); loadMeInternal(); _state.value = _state.value.copy(screen = AppScreen.SETTINGS, message = "字段和选项已刷新") }
    fun logout() = task { runCatching { api.logout() }; settings.clearSession(); _state.value = MultiplayerState(screen = AppScreen.WELCOME) }
    fun askDelete() = set { copy(deleteConfirm = true) }
    fun cancelDelete() = set { copy(deleteConfirm = false) }
    fun deleteAccount() = task { api.deleteAccount(); settings.clearSession(); _state.value = MultiplayerState(screen = AppScreen.WELCOME, message = "账户已注销，飞书表格未删除") }
    fun dismissSuccess() = set { copy(saveSucceeded = false) }

    private fun draftsFrom(result: InspectResponse): List<MappingDraft> = result.suggestions.map { suggestion ->
        val picked = suggestion.suggestedField ?: if (suggestion.semanticKey == "purpose") suggestion.compatibleFields.firstOrNull { it.primary } else null
        MappingDraft(suggestion.semanticKey, true, suggestion.required, picked?.name ?: if (suggestion.status == "conflict") "${suggestion.standardName}（记账）" else suggestion.standardName,
            suggestion.expectedType, picked?.id, if (picked != null) suggestion.status else "missing", picked?.options?.joinToString("、").orEmpty())
    }

    private fun defaultMappings() = listOf(
        MappingDraft("date", true, true, "记账日期", 5), MappingDraft("purpose", true, true, "用途", 1),
        MappingDraft("amount", true, true, "金额", 2), MappingDraft("paymentPlatform", true, false, "支付平台", 3, optionsText = "微信支付、支付宝、银行卡"),
        MappingDraft("tags", true, false, "标签", 4, optionsText = "餐饮、交通、购物、居住、医疗、娱乐、办公、差旅"),
        MappingDraft("note", true, false, "备注", 1), MappingDraft("project", true, false, "归属项目", 3)
    )

    private fun validateMappings(mappings: List<MappingDraft>) {
        for (key in listOf("date", "purpose", "amount")) require(mappings.any { it.semanticKey == key && it.enabled }) { "记账日期、用途和金额必须启用" }
        require(mappings.filter { it.enabled }.all { it.displayName.isNotBlank() }) { "已启用字段的名称不能为空" }
    }

    private fun set(transform: MultiplayerState.() -> MultiplayerState) { _state.value = _state.value.transform() }
    private fun task(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiFailure && e.status == 401 && settings.sessionToken.isNotBlank()) requireLogin()
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    companion object {
        fun now(): String = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
