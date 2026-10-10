package com.example.screenshotbookkeeping

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

data class UiState(
    val loginRequired: Boolean = false,
    val configured: Boolean = false, val config: AppConfig? = null, val form: LedgerForm = LedgerForm(),
    val busy: Boolean = false, val message: String? = null, val error: String? = null,
    val settingsOpen: Boolean = false, val imageUri: Uri? = null, val requestId: String = UUID.randomUUID().toString(),
    val demoMode: Boolean = false, val saveSucceeded: Boolean = false, val workerUrl: String = "",
    val books: PersonalBookCatalogDto = PersonalBookCatalogDto(), val bookScreen: BookScreen = BookScreen.NONE,
    val bookUrl: String = "", val inspection: PersonalInspectResponse? = null,
    val mappingDrafts: List<MappingDraft> = emptyList(), val switchPromptBookId: String? = null,
    val fieldsConfirmPending: Boolean = false, val toastMessage: String? = null,
    val fieldLayoutDrafts: List<DynamicFieldDto> = emptyList(),
    val fieldSettingsSaving: Boolean = false, val fieldSettingsUpdated: Boolean = false,
    val fieldSettingsRevision: Int = 0,
    val optionSuggestions: Map<String, List<String>> = emptyMap(),
    val processingBookId: String? = null,
    val openFieldsAfterSwitch: Boolean = false,
    val bookToastMessage: String? = null,
    val manageFieldsReturnScreen: BookScreen = BookScreen.NONE,
    val managingBookId: String? = null,
    val operationMessage: String? = null,
    val serviceBuild: String? = null
)

enum class BookScreen { NONE, LIST, MANAGE, CONNECT, MAP_FIELDS, MANAGE_FIELDS }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SecureSettings(application)
    private val api = ApiClient(settings)
    private val taskMutex = Mutex()
    private val _state = MutableStateFlow(UiState(
        configured = settings.isConfigured,
        settingsOpen = !settings.isConfigured,
        workerUrl = settings.workerUrl.ifBlank { BuildConfig.SERVICE_URL }
    ))
    val state = _state.asStateFlow()

    init {
        if (settings.isConfigured) {
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<PersonalBookCatalogDto>(settings.cachedCatalog) }
                .getOrNull()?.let { books ->
                    books.books.firstOrNull { it.id == books.currentBookId && it.fields.isNotEmpty() }?.let { book ->
                        val config = configFromBook(book)
                        _state.value = _state.value.copy(books = books, config = config, form = dynamicEmptyForm(config))
                        settings.currentBookId = book.id
                    }
                }
            if (_state.value.config == null) refreshConfig()
        }
    }

    fun saveSettings(url: String, key: String, targetUrl: String) {
        settings.workerUrl = url
        if (key.isNotBlank()) settings.accessKey = key
        _state.value = _state.value.copy(
            configured = settings.isConfigured,
            settingsOpen = true,
            demoMode = false,
            workerUrl = settings.workerUrl,
            message = null,
            error = null
        )
        if (settings.isConfigured) launchTask {
            val health = api.health()
            val config = if (targetUrl.isNotBlank() && targetUrl != _state.value.config?.targetUrl) api.setTarget(targetUrl) else api.config()
            _state.value = _state.value.copy(
                config = config,
                books = api.books(),
                settingsOpen = false,
                serviceBuild = health.build,
                message = if (targetUrl.isNotBlank()) "已切换记账表格" else "服务连接正常"
            )
        }
    }
    fun openSettings() { _state.value = _state.value.copy(settingsOpen = true) }
    fun closeSettings() { if (_state.value.configured) _state.value = _state.value.copy(settingsOpen = false) }
    fun renameFields(names: Map<String, String>) = launchTask {
        val config = api.renameFields(names)
        _state.value = _state.value.copy(config = config, settingsOpen = true, message = "表头已同步修改到飞书")
    }
    fun enterDemo() {
        _state.value = _state.value.copy(
            configured = true,
            settingsOpen = false,
            demoMode = true,
            config = AppConfig(listOf("支付宝", "微信支付", "银行卡"), listOf("餐饮", "咖啡", "工作"), listOf("日常生活", "差旅报销")),
            form = LedgerForm(
                date = now(), purpose = "瑞幸咖啡 · 生椰拿铁", amount = "18.00",
                paymentPlatform = "支付宝", tags = setOf("餐饮"), note = "上海静安寺店", project = "日常生活"
            ),
            message = "演示模式：AI 已识别支付结果，请确认后保存",
            error = null
        )
    }
    fun updateForm(form: LedgerForm) {
        _state.value.config?.fields.orEmpty().filter { it.fieldType in 3..4 }.forEach { field ->
            val value = form.dynamicValues[field.fieldId].orEmpty()
            if (value != _state.value.form.dynamicValues[field.fieldId] && value.isNotBlank())
                settings.rememberOptions(field.fieldId, value.split('、'))
        }
        _state.value = _state.value.copy(form = form, message = null)
    }
    fun newManual() {
        when {
            _state.value.busy -> {
                _state.value = _state.value.copy(toastMessage = "正在加载账本，请稍候")
                return
            }
            !_state.value.configured || _state.value.config == null -> {
                _state.value = _state.value.copy(toastMessage = "请先连接账本；“＋”用于新建一笔空白记账")
                return
            }
        }
        _state.value = _state.value.copy(
            form = dynamicEmptyForm(_state.value.config),
            imageUri = null,
            message = null,
            error = null,
            requestId = UUID.randomUUID().toString(),
            saveSucceeded = false,
            optionSuggestions = emptyMap(),
            toastMessage = "可以开始记下一笔了"
        )
    }
    fun dismissSuccess() { _state.value = _state.value.copy(saveSucceeded = false) }
    fun consumeToast() { _state.value = _state.value.copy(toastMessage = null) }
    fun consumeFieldSettingsUpdated() { _state.value = _state.value.copy(fieldSettingsUpdated = false) }
    fun consumeBookToast() { _state.value = _state.value.copy(bookToastMessage = null) }

    fun refreshConfig() = launchTask {
        val books = api.books()
        settings.currentBookId = books.currentBookId.orEmpty()
        val config = books.books.firstOrNull { it.id == books.currentBookId && it.fields.isNotEmpty() }?.let(::configFromBook) ?: api.config()
        val hydrated = books.copy(books = books.books.map { if (it.id == books.currentBookId) it.copy(fields = config.fields) else it })
        settings.currentBookId = books.currentBookId.orEmpty()
        settings.cachedCatalog = Json.encodeToString(hydrated)
        _state.value = _state.value.copy(config = config, books = hydrated, settingsOpen = false,
            form = if (hasDraft(_state.value)) _state.value.form else dynamicEmptyForm(config))
    }

    fun refreshFromToolbar() {
        when {
            _state.value.busy -> {
                _state.value = _state.value.copy(toastMessage = "正在加载账本，请稍候")
                return
            }
            !_state.value.configured -> {
                _state.value = _state.value.copy(toastMessage = "请先连接账本；刷新按钮用于同步当前账本的字段和选项")
                return
            }
        }
        _state.value = _state.value.copy(
            form = dynamicEmptyForm(_state.value.config),
            imageUri = null,
            requestId = UUID.randomUUID().toString(),
            message = null,
            error = null,
            optionSuggestions = emptyMap(),
            saveSucceeded = false,
            toastMessage = "已清空输入，正在同步当前账本的字段和选项…"
        )
        launchTask("正在同步飞书字段和选项…") {
            val books = api.refreshCurrentPersonalBook()
            val config = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: api.config()
            _state.value = _state.value.copy(
                config = config,
                books = books,
                form = dynamicEmptyForm(config),
                imageUri = null,
                requestId = UUID.randomUUID().toString(),
                optionSuggestions = emptyMap(),
                settingsOpen = false,
                toastMessage = "当前页面已刷新，字段和选项已同步"
            )
        }
    }

    fun openBooks() {
        _state.value = _state.value.copy(bookScreen = BookScreen.MANAGE, error = null, message = null)
        if (_state.value.books.books.isEmpty()) launchTask {
            val books = api.books()
            val config = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: _state.value.config
            _state.value = _state.value.copy(books = books, config = config)
        }
    }
    fun openBookManagement() {
        _state.value = _state.value.copy(bookScreen = BookScreen.MANAGE, error = null, message = null)
    }
    fun closeBooks() { _state.value = _state.value.copy(bookScreen = BookScreen.NONE, inspection = null, mappingDrafts = emptyList(), error = null) }
    fun startBookkeepingFromManagedBook() {
        _state.value = _state.value.copy(
            bookScreen = BookScreen.NONE,
            inspection = null,
            mappingDrafts = emptyList(),
            form = dynamicEmptyForm(_state.value.config),
            imageUri = null,
            requestId = UUID.randomUUID().toString(),
            message = null,
            error = null
        )
    }
    fun startConnectBook() { _state.value = _state.value.copy(bookScreen = BookScreen.CONNECT, bookUrl = "", inspection = null, mappingDrafts = emptyList(), error = null) }
    fun setBookUrl(value: String) { _state.value = _state.value.copy(bookUrl = value, error = null) }
    fun inspectBook(tableId: String? = null) = launchTask {
        val result = api.inspectPersonalBook(_state.value.bookUrl.trim(), tableId)
        _state.value = if (result.selectionRequired) _state.value.copy(inspection = result, message = "请选择要记账的数据表")
        else _state.value.copy(inspection = result, mappingDrafts = emptyList(), bookScreen = BookScreen.MAP_FIELDS, message = null)
    }
    fun updateBookMapping(index: Int, transform: (MappingDraft) -> MappingDraft) {
        val list = _state.value.mappingDrafts.toMutableList(); list[index] = transform(list[index])
        _state.value = _state.value.copy(mappingDrafts = list, error = null)
    }
    fun connectInspectedBook() = launchTask {
        val inspection = _state.value.inspection ?: error("请先读取表格")
        val table = inspection.table ?: error("请选择数据表")
        val books = api.connectPersonalBook(PersonalConnectRequest(
            UUID.randomUUID().toString(), inspection.appToken, table.table_id, inspection.sourceUrl,
            inspection.target.copy(tableId = table.table_id, sourceUrl = inspection.sourceUrl), emptyList()
        ))
        val config = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: api.config()
        _state.value = _state.value.copy(books = books, config = config, bookScreen = BookScreen.NONE, inspection = null,
            mappingDrafts = emptyList(), form = dynamicEmptyForm(config), imageUri = null, message = null, toastMessage = "已切换到“${config.tableName}”")
    }
    fun requestSwitchBook(bookId: String) {
        if (bookId == _state.value.books.currentBookId) { closeBooks(); return }
        if (hasDraft(_state.value)) _state.value = _state.value.copy(switchPromptBookId = bookId, openFieldsAfterSwitch = false)
        else switchBook(bookId, false, false)
    }
    fun openBookFields(bookId: String) {
        if (bookId == _state.value.books.currentBookId) { enterManageCurrentFields(BookScreen.MANAGE); return }
        if (hasDraft(_state.value)) _state.value = _state.value.copy(switchPromptBookId = bookId, openFieldsAfterSwitch = true)
        else switchBook(bookId, false, true)
    }
    fun cancelSwitch() { _state.value = _state.value.copy(switchPromptBookId = null, openFieldsAfterSwitch = false) }
    fun discardAndSwitch() { _state.value.switchPromptBookId?.let { switchBook(it, false, _state.value.openFieldsAfterSwitch) } }
    fun saveAndSwitch() { _state.value.switchPromptBookId?.let { switchBook(it, true, _state.value.openFieldsAfterSwitch) } }
    fun reparseAndSwitch() {
        val current = _state.value
        val bookId = current.switchPromptBookId ?: return
        if (current.imageUri == null) {
            _state.value = current.copy(toastMessage = "没有可重新识别的截图，请重新选择截图")
            return
        }
        switchBook(bookId, false, current.openFieldsAfterSwitch, current.imageUri)
    }
    private fun switchBook(bookId: String, saveDraft: Boolean, openFields: Boolean, reparseUri: Uri? = null) {
        val beforeSwitch = _state.value
        val localBook = beforeSwitch.books.books.firstOrNull { it.id == bookId }
        if (openFields && !saveDraft && localBook != null) {
            val localConfig = configFromBook(localBook)
            _state.value = beforeSwitch.copy(
                config = localConfig,
                bookScreen = BookScreen.MANAGE_FIELDS,
                manageFieldsReturnScreen = BookScreen.MANAGE,
                managingBookId = bookId,
                fieldLayoutDrafts = localConfig.fields,
                mappingDrafts = emptyList(),
                form = dynamicEmptyForm(localConfig),
                imageUri = null,
                requestId = UUID.randomUUID().toString(),
                switchPromptBookId = null,
                processingBookId = bookId,
                fieldSettingsUpdated = false,
                bookToastMessage = null,
                error = null
            )
        } else {
            _state.value = _state.value.copy(switchPromptBookId = null, processingBookId = bookId)
        }
        launchTask {
            try {
                if (saveDraft) saveCurrentFormOrThrow()
                val books = api.switchBook(bookId)
                settings.currentBookId = bookId
                settings.cachedCatalog = Json.encodeToString(books)
                val selected = books.books.firstOrNull { it.id == books.currentBookId }
                    ?: throw IllegalStateException("切换后未找到当前账本")
                val config = configFromBook(selected)
                _state.value = _state.value.copy(
                    books = books,
                    config = config,
                    bookScreen = if (openFields) BookScreen.MANAGE_FIELDS else BookScreen.NONE,
                    manageFieldsReturnScreen = if (openFields) BookScreen.MANAGE else BookScreen.NONE,
                    managingBookId = if (openFields) bookId else null,
                    fieldLayoutDrafts = if (openFields) config.fields else emptyList(),
                    switchPromptBookId = null,
                    openFieldsAfterSwitch = false,
                    form = dynamicEmptyForm(config),
                    imageUri = reparseUri,
                    requestId = UUID.randomUUID().toString(),
                    message = if (reparseUri != null) "已切换账本，正在按新字段重新识别…" else null,
                    toastMessage = if (openFields) null else "已切换到“${config.tableName}”"
                )
                if (reparseUri != null) acceptSharedImage(reparseUri)
            } catch (error: Exception) {
                if (openFields && !saveDraft) _state.value = beforeSwitch
                throw error
            }
        }
    }
    fun manageCurrentFields() = enterManageCurrentFields(BookScreen.NONE)

    private fun enterManageCurrentFields(returnScreen: BookScreen) {
        val book = _state.value.books.books.find { it.id == _state.value.books.currentBookId } ?: return
        if (_state.value.config?.fields?.isNotEmpty() == true) {
            _state.value = _state.value.copy(
                bookScreen = BookScreen.MANAGE_FIELDS,
                manageFieldsReturnScreen = returnScreen,
                managingBookId = book.id,
                mappingDrafts = emptyList(),
                fieldLayoutDrafts = _state.value.config?.fields.orEmpty(),
                fieldSettingsUpdated = false,
                bookToastMessage = null,
                error = null
            )
            return
        }
        val byKey = book.mappings.associateBy { it.semanticKey }
        _state.value = _state.value.copy(bookScreen = BookScreen.MANAGE_FIELDS, manageFieldsReturnScreen = returnScreen, mappingDrafts = semanticKeys().map { key ->
            val mapping = byKey[key]
            MappingDraft(key, mapping != null, key in setOf("date", "purpose", "amount"), mapping?.displayName ?: semanticName(key),
                expectedType(key), mapping?.fieldId, if (mapping == null) "missing" else "matched", mapping?.options?.joinToString("、").orEmpty())
        }, managingBookId = book.id, fieldSettingsUpdated = false, bookToastMessage = null, error = null)
    }
    fun closeManagedFields() {
        val destination = _state.value.manageFieldsReturnScreen
        _state.value = _state.value.copy(
            bookScreen = destination,
            manageFieldsReturnScreen = BookScreen.NONE,
            managingBookId = null,
            mappingDrafts = emptyList(),
            fieldLayoutDrafts = emptyList(),
            error = null
        )
    }
    fun requestFieldsConfirmation() {
        if (_state.value.fieldLayoutDrafts.isNotEmpty()) {
            _state.value = _state.value.copy(fieldsConfirmPending = true)
            return
        }
        runCatching { validateMappingDrafts(_state.value.mappingDrafts) }
            .onSuccess { _state.value = _state.value.copy(fieldsConfirmPending = true) }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }
    fun cancelFieldsConfirmation() { _state.value = _state.value.copy(fieldsConfirmPending = false) }
    fun confirmFieldChanges() = launchTask {
        if (_state.value.fieldLayoutDrafts.isNotEmpty()) {
            val books = api.updateCurrentBookLayout(_state.value.fieldLayoutDrafts)
            val config = api.config()
            _state.value = _state.value.copy(books = books, config = config, fieldsConfirmPending = false, bookScreen = BookScreen.MANAGE, message = "字段设置已保存")
            return@launchTask
        }
        validateMappingDrafts(_state.value.mappingDrafts)
        val books = api.updateCurrentBookFields(_state.value.mappingDrafts.map(MappingDraft::toDto))
        val config = api.config()
        _state.value = _state.value.copy(books = books, config = config, fieldsConfirmPending = false, bookScreen = BookScreen.MANAGE, message = "字段设置已同步到飞书")
    }
    fun refreshCurrentBook() = launchTask {
        val books = api.refreshCurrentPersonalBook()
        val config = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: api.config()
        _state.value = _state.value.copy(books = books, config = config, message = null, bookToastMessage = "账本名称、字段和选项已刷新")
    }
    fun refreshBookCatalog() = launchTask {
        val books = api.books()
        val config = if (books.currentBookId != null) api.config() else _state.value.config
        _state.value = _state.value.copy(books = books, config = config, message = null, bookToastMessage = "全部账本信息已刷新")
    }
    fun removeBook(bookId: String) = launchTask { _state.value = _state.value.copy(books = api.removeBook(bookId), message = "已从常用账本移除，飞书数据未删除") }

    fun moveManagedField(from: Int, to: Int) {
        val list = _state.value.fieldLayoutDrafts.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from); list.add(to, item)
        val config = _state.value.config
        val hidden = config?.fields.orEmpty().filter { field -> list.none { it.fieldId == field.fieldId } }
        _state.value = _state.value.copy(fieldLayoutDrafts = list, config = config?.copy(fields = list + hidden), fieldSettingsUpdated = false, fieldSettingsRevision = _state.value.fieldSettingsRevision + 1)
    }
    fun moveManagedField(fieldId: String, direction: Int) {
        val from = _state.value.fieldLayoutDrafts.indexOfFirst { it.fieldId == fieldId }
        moveManagedField(from, from + direction)
    }
    fun restoreManagedFieldOrder(ids: List<String>) {
        val current = _state.value.fieldLayoutDrafts
        val byId = current.associateBy { it.fieldId }
        val restored = ids.mapNotNull(byId::get) + current.filter { it.fieldId !in ids }
        val config = _state.value.config
        val hidden = config?.fields.orEmpty().filter { field -> restored.none { it.fieldId == field.fieldId } }
        _state.value = _state.value.copy(fieldLayoutDrafts = restored, config = config?.copy(fields = restored + hidden))
    }
    fun setManagedFieldRequired(fieldId: String, required: Boolean) {
        val fields = _state.value.fieldLayoutDrafts.map { if (it.fieldId == fieldId) it.copy(required = required) else it }
        val config = _state.value.config
        _state.value = _state.value.copy(
            fieldLayoutDrafts = fields,
            config = config?.copy(fields = config.fields.map { if (it.fieldId == fieldId) it.copy(required = required) else it }),
            fieldSettingsSaving = true,
            fieldSettingsUpdated = false,
            fieldSettingsRevision = _state.value.fieldSettingsRevision + 1
        )
        persistManagedFieldLayout()
    }
    fun moveMainField(from: Int, to: Int) {
        val config = _state.value.config ?: return
        val visible = config.fields.filter { it.fieldType in 1..5 }.toMutableList()
        if (from !in visible.indices || to !in visible.indices || from == to) return
        val item = visible.removeAt(from); visible.add(to, item)
        val hidden = config.fields.filterNot { it.fieldType in 1..5 }
        _state.value = _state.value.copy(config = config.copy(fields = visible + hidden))
    }
    fun moveMainField(fieldId: String, direction: Int) {
        val visible = _state.value.config?.fields.orEmpty().filter { it.fieldType in 1..5 }
        val from = visible.indexOfFirst { it.fieldId == fieldId }
        moveMainField(from, from + direction)
    }
    fun restoreMainFieldOrder(ids: List<String>) {
        val config = _state.value.config ?: return
        val visible = config.fields.filter { it.fieldType in 1..5 }
        val byId = visible.associateBy { it.fieldId }
        val restored = ids.mapNotNull(byId::get) + visible.filter { it.fieldId !in ids }
        _state.value = _state.value.copy(config = config.copy(fields = restored + config.fields.filterNot { it.fieldType in 1..5 }))
    }
    fun persistMainFieldOrder() = launchSilentTask {
        val fields = _state.value.config?.fields ?: return@launchSilentTask
        val books = api.updateCurrentBookLayout(fields)
        _state.value = _state.value.copy(books = books)
    }
    fun persistManagedFieldLayout() {
        val revision = _state.value.fieldSettingsRevision
        launchSilentTask {
            val fields = _state.value.fieldLayoutDrafts
            if (fields.isEmpty()) return@launchSilentTask
            _state.value = _state.value.copy(fieldSettingsSaving = true, fieldSettingsUpdated = false)
            val books = api.updateCurrentBookLayout(fields)
            val serverConfig = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: _state.value.config ?: return@launchSilentTask
            val latest = _state.value.fieldLayoutDrafts
            val serverById = serverConfig.fields.associateBy { it.fieldId }
            val merged = latest.map { local -> serverById[local.fieldId]?.copy(required = local.required) ?: local } + serverConfig.fields.filter { fresh -> latest.none { it.fieldId == fresh.fieldId } }
            val isLatest = _state.value.fieldSettingsRevision == revision
            _state.value = _state.value.copy(books = books, config = serverConfig.copy(fields = merged), fieldSettingsSaving = !isLatest, fieldSettingsUpdated = isLatest)
        }
    }
    fun addSuggestedOption(fieldId: String, option: String) = launchTask("正在添加飞书选项…") {
        val books = api.addCurrentFieldOption(fieldId, option)
        val config = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: api.config()
        val field = config.fields.find { it.fieldId == fieldId }
        val current = _state.value.form.dynamicValues[fieldId].orEmpty()
        val selected = if (field?.fieldType == 4) (current.split("、").filter { it.isNotBlank() } + option).distinct().joinToString("、") else option
        val remaining = _state.value.optionSuggestions[fieldId].orEmpty().filterNot { it == option }
        _state.value = _state.value.copy(
            books = books, config = config,
            form = _state.value.form.copy(dynamicValues = _state.value.form.dynamicValues + (fieldId to selected)),
            optionSuggestions = if (remaining.isEmpty()) _state.value.optionSuggestions - fieldId else _state.value.optionSuggestions + (fieldId to remaining),
            toastMessage = "已新增并选中“$option”"
        )
    }
    fun addFieldOption(fieldId: String, option: String) = launchTask("正在添加飞书选项…") {
        try {
            val books = api.addCurrentFieldOption(fieldId, option)
            val freshConfig = books.books.firstOrNull { it.id == books.currentBookId }?.let(::configFromBook) ?: api.config()
            val field = freshConfig.fields.find { it.fieldId == fieldId }
            val orderedConfig = freshConfig.copy(fields = freshConfig.fields.map { item ->
                if (item.fieldId == fieldId) item.copy(options = listOf(option) + item.options.filterNot { it == option }) else item
            })
            val current = _state.value.form.dynamicValues[fieldId].orEmpty()
            val selected = if (field?.fieldType == 4) {
                (current.split("、").filter { it.isNotBlank() } + option).distinct().joinToString("、")
            } else option
            _state.value = _state.value.copy(
                books = books,
                config = orderedConfig,
                form = _state.value.form.copy(dynamicValues = _state.value.form.dynamicValues + (fieldId to selected)),
                toastMessage = "已添加并选中“$option”"
            )
        } catch (error: Exception) { throw error }
    }
    fun updateFieldOptions(fieldId: String, options: List<String>) = launchTask("正在同步飞书选项…") {
        val existing = _state.value.config?.fields?.find { it.fieldId == fieldId }?.options.orEmpty()
        val removed = existing.filter { it !in options }
        val additions = options.filter { it !in existing }.distinct()
        var books = _state.value.books ?: api.books()
        val renamed = removed.size == 1 && additions.size == 1 && options.size == existing.size
        if (renamed) books = api.renameCurrentFieldOption(fieldId, removed.single(), additions.single())
        else {
            removed.forEach { option -> books = api.hideCurrentFieldOption(fieldId, option) }
            additions.forEach { option -> books = api.addCurrentFieldOption(fieldId, option) }
        }
        val selectedBook = books.books.firstOrNull { it.id == books.currentBookId }
        val config = selectedBook?.let(::configFromBook) ?: api.config()
        val current = _state.value.form.dynamicValues[fieldId].orEmpty()
        val field = config.fields.find { it.fieldId == fieldId }
        val cleaned = if (field?.fieldType == 4) current.split("、").filter { it in options }.joinToString("、") else current.takeIf { it in options }.orEmpty()
        _state.value = _state.value.copy(
            books = books, config = config,
            form = _state.value.form.copy(dynamicValues = _state.value.form.dynamicValues + (fieldId to cleaned)),
            toastMessage = when {
                renamed -> "选项已重命名，历史记录保持不变"
                removed.isNotEmpty() -> "选项已停用，历史记录保持不变"
                additions.isNotEmpty() -> "新选项已添加"
                else -> null
            }
        )
    }
    fun dismissSuggestedOption(fieldId: String, option: String) {
        val remaining = _state.value.optionSuggestions[fieldId].orEmpty().filterNot { it == option }
        _state.value = _state.value.copy(optionSuggestions = if (remaining.isEmpty()) _state.value.optionSuggestions - fieldId else _state.value.optionSuggestions + (fieldId to remaining))
    }

    fun acceptSharedImage(uri: Uri) {
        _state.value = _state.value.copy(
            imageUri = uri,
            message = "已收到截图，正在识别…",
            error = null,
            requestId = UUID.randomUUID().toString(),
            optionSuggestions = emptyMap()
        )
        if (!settings.isConfigured) { _state.value = _state.value.copy(settingsOpen = true); return }
        parseWhenReady(uri)
    }

    fun acceptPaymentNotification(notice: PaymentNotice) {
        if (_state.value.config == null && settings.isConfigured) {
            viewModelScope.launch {
                try {
                    withTimeout(65_000) { while (_state.value.busy) delay(50) }
                    if (_state.value.config == null) {
                        _state.value = _state.value.copy(error = "账本未加载成功，请刷新后再次点击记账提醒")
                    } else acceptPaymentNotification(notice)
                } catch (_: TimeoutCancellationException) {
                    _state.value = _state.value.copy(error = "账本加载超时，请刷新后再次点击记账提醒")
                }
            }
            return
        }
        val config = _state.value.config
        val amount = notice.amount.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(it)) }
            ?: notice.amount
        val form = if (config?.fields?.isNotEmpty() == true) {
            val values = buildMap {
                config.fields.forEach { field ->
                    val name = field.displayName
                    when {
                        field.fieldType == 5 && name.contains(Regex("日期|时间")) -> put(field.fieldId, now())
                        field.fieldType == 2 && name.contains(Regex("金额|支出|费用|付款")) -> put(field.fieldId, amount)
                        (field.primary || name.contains(Regex("名称|商户|用途|目的|摘要"))) && field.fieldType == 1 -> put(field.fieldId, notice.merchant)
                        (field.fieldType == 3 || field.fieldType == 4) && name.contains(Regex("支付平台|支付方式|付款方式")) && notice.platform in field.options -> put(field.fieldId, notice.platform)
                    }
                }
            }
            LedgerForm(dynamicValues = values)
        } else {
            LedgerForm(
                date = now(),
                purpose = notice.merchant,
                amount = amount,
                paymentPlatform = notice.platform,
                note = "来自支付成功通知"
            )
        }
        _state.value = _state.value.copy(
            form = form,
            bookScreen = BookScreen.NONE,
            settingsOpen = false,
            imageUri = null,
            requestId = UUID.randomUUID().toString(),
            message = "已根据${notice.platform.ifBlank { "支付" }}通知填充，请确认后保存",
            error = null,
            saveSucceeded = false,
            optionSuggestions = emptyMap(),
            toastMessage = "已填入 ¥$amount · ${notice.merchant}"
        )
    }

    fun retryParse() { _state.value.imageUri?.let(::parseWhenReady) }
    private fun parseWhenReady(uri: Uri) {
        viewModelScope.launch {
            try {
                withTimeout(65_000) { while (_state.value.busy) delay(50) }
                if (_state.value.imageUri == uri) parse(uri)
            } catch (_: TimeoutCancellationException) {
                _state.value = _state.value.copy(error = "前一项操作尚未完成，请稍后点击重新识别", message = null)
            }
        }
    }
    private fun parse(uri: Uri) = launchTask {
        val parseRequestId = _state.value.requestId
        if (_state.value.config == null) {
            _state.value = _state.value.copy(config = api.config(), settingsOpen = false)
        }
        val retainedUri = withContext(Dispatchers.IO) {
            if (uri.scheme == "file") uri else {
                val folder = java.io.File(getApplication<Application>().cacheDir, "recognition").apply { mkdirs() }
                folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
                val file = java.io.File(folder, "${_state.value.requestId}.image")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: error("截图已无法读取，请重新分享截图")
                Uri.fromFile(file)
            }
        }
        if (_state.value.requestId != parseRequestId) return@launchTask
        _state.value = _state.value.copy(imageUri = retainedUri)
        val encoded = withContext(Dispatchers.Default) { ImageEncoder.toJpegDataUrl(getApplication(), retainedUri) }
        val input = ParseRequest(encoded, now())
        var result = api.parse(input)
        if (result.supported && result.values.isEmpty() && result.suggestedOptions.isEmpty() && _state.value.config?.fields?.isNotEmpty() == true) {
            val books = api.refreshCurrentPersonalBook()
            if (_state.value.requestId != parseRequestId) return@launchTask
            val book = books.books.firstOrNull { it.id == settings.currentBookId }
                ?: error("重新识别前未找到当前账本，请刷新账本")
            _state.value = _state.value.copy(books = books, config = configFromBook(book))
            result = api.parse(input)
            if (result.supported && result.values.isEmpty() && result.suggestedOptions.isEmpty()) {
                error("识别结果没有可填信息，截图和当前输入已保留，请重试或手动填写")
            }
        }
        if (_state.value.requestId != parseRequestId) return@launchTask
        if (!result.supported) {
            _state.value = _state.value.copy(form = dynamicEmptyForm(_state.value.config), optionSuggestions = emptyMap(), error = "这不像单笔支付结果截图，请手动填写。")
        } else if (_state.value.config?.fields?.isNotEmpty() == true) {
            val fields = _state.value.config?.fields.orEmpty().associateBy { it.fieldId }
            val sanitized = sanitizeRecognizedValues(fields, result.values)
            val displayed = sanitized.mapValues { (id, value) -> when {
                fields[id]?.fieldType == 5 -> toDisplayDate(value)
                fields[id]?.fieldType == 2 && fields[id]?.displayName?.contains(Regex("金额|支出|费用")) == true -> String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(value.toDoubleOrNull() ?: 0.0))
                else -> value
            } }
            _state.value = _state.value.copy(form = dynamicEmptyForm(_state.value.config).copy(dynamicValues = dynamicEmptyForm(_state.value.config).dynamicValues + displayed),
                optionSuggestions = result.suggestedOptions,
                message = result.warnings.takeIf { it.isNotEmpty() }?.joinToString("；"))
        } else {
            val low = buildSet {
                if (result.date == null || result.confidence.date < .7) add("date")
                if (result.purpose == null || result.confidence.purpose < .7) add("purpose")
                if (result.amount == null || result.confidence.amount < .7) add("amount")
            }
            _state.value = _state.value.copy(form = LedgerForm(
                date = result.date ?: now(), purpose = result.purpose.orEmpty(), amount = result.amount?.let { "%.2f".format(it) }.orEmpty(),
                paymentPlatform = result.paymentPlatform.orEmpty(), tags = result.tags.toSet(), note = result.note.orEmpty(),
                project = result.project.orEmpty(), lowConfidence = low
            ), message = result.warnings.takeIf { it.isNotEmpty() }?.joinToString("；"))
        }
    }

    private fun sanitizeRecognizedValues(
        fields: Map<String, DynamicFieldDto>,
        source: Map<String, String>
    ): Map<String, String> {
        val cleaned = source.toMutableMap()
        val choiceFields = fields.values.filter { it.fieldType == 3 || it.fieldType == 4 }
        source.forEach { (fieldId, rawValue) ->
            val field = fields[fieldId] ?: return@forEach
            when (field.fieldType) {
                2 -> if (rawValue.toDoubleOrNull() == null) cleaned.remove(fieldId)
                5 -> if (!looksLikeDate(rawValue)) cleaned.remove(fieldId)
                3, 4 -> {
                    val tokens = rawValue.split('、', ',', '，').map(String::trim).filter(String::isNotEmpty)
                    val validHere = tokens.filter { it in field.options }
                    tokens.filterNot { it in field.options }.forEach { token ->
                        val owner = choiceFields.singleOrNull { token in it.options }
                        if (owner != null) {
                            val existing = cleaned[owner.fieldId].orEmpty()
                            cleaned[owner.fieldId] = if (owner.fieldType == 4) {
                                (existing.split('、').filter(String::isNotBlank) + token).distinct().joinToString("、")
                            } else token
                        }
                    }
                    if (validHere.isEmpty()) cleaned.remove(fieldId)
                    else cleaned[fieldId] = if (field.fieldType == 4) validHere.distinct().joinToString("、") else validHere.first()
                }
            }
        }
        return cleaned
    }

    private fun looksLikeDate(value: String): Boolean {
        val clean = value.trim()
        if (!clean.contains(Regex("\\d{4}[-/.年]\\d{1,2}"))) return false
        return runCatching { OffsetDateTime.parse(clean) }.isSuccess ||
            runCatching { java.time.LocalDateTime.parse(clean, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.isSuccess ||
            runCatching { java.time.LocalDate.parse(clean.take(10).replace('/', '-').replace('.', '-')) }.isSuccess ||
            clean.matches(Regex("\\d{4}年\\d{1,2}月\\d{1,2}日.*"))
    }

    fun save() {
        val error = validationError(_state.value)
        if (error != null) { _state.value = _state.value.copy(error = null, toastMessage = error); return }
        if (_state.value.demoMode) {
            _state.value = _state.value.copy(saveSucceeded = true, error = null)
            return
        }
        launchTask("正在写入飞书…") {
            saveCurrentFormOrThrow()
            _state.value = _state.value.copy(
                form = dynamicEmptyForm(_state.value.config),
                message = null,
                error = null,
                imageUri = null,
                requestId = UUID.randomUUID().toString(),
                optionSuggestions = emptyMap(),
                saveSucceeded = true
            )
        }
    }

    private suspend fun saveCurrentFormOrThrow() {
        validationError(_state.value)?.let { throw IllegalArgumentException(it) }
        val form = _state.value.form
        val dynamic = _state.value.config?.fields?.isNotEmpty() == true
        api.save(RecordRequest(_state.value.requestId, form.date, form.purpose.trim(), form.amount.toDoubleOrNull() ?: 0.0, form.paymentPlatform, form.tags.toList(), form.note.ifBlank { null }, form.project.ifBlank { null }, if (dynamic) form.dynamicValues else null))
    }

    private fun validationError(state: UiState): String? {
        val form = state.form; val amount = form.amount.toDoubleOrNull()
        if (state.config?.fields?.isNotEmpty() == true) return when {
            !state.demoMode && state.config == null -> "请先设置记账表格"
            state.config.fields.any { it.required && form.dynamicValues[it.fieldId].isNullOrBlank() } -> "请填写必填字段：${state.config.fields.first { it.required && form.dynamicValues[it.fieldId].isNullOrBlank() }.displayName}"
            form.dynamicValues.values.none { it.isNotBlank() } -> "请至少填写一个字段"
            else -> null
        }
        val platformEnabled = state.config?.fieldNames?.let { it.isEmpty() || it.containsKey("支付平台") } ?: true
        return when {
            !state.demoMode && state.config == null -> "请先设置记账表格"
            form.date.isBlank() || (runCatching { OffsetDateTime.parse(form.date) }.isFailure && runCatching { java.time.LocalDateTime.parse(form.date, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.isFailure) -> "请输入有效日期时间"
            form.purpose.isBlank() -> "请输入用途"
            amount == null || amount <= 0 || abs(amount * 100 - round(amount * 100)) > 0.000001 -> "金额必须为正数且最多两位小数"
            platformEnabled && form.paymentPlatform.isBlank() -> "请选择支付平台"
            else -> null
        }
    }

    private fun hasDraft(state: UiState): Boolean {
        val fieldTypes = state.config?.fields.orEmpty().associate { it.fieldId to it.fieldType }
        val dynamicContent = state.form.dynamicValues.any { (fieldId, value) -> fieldTypes[fieldId] != 5 && value.isNotBlank() }
        return state.imageUri != null || dynamicContent || state.form.purpose.isNotBlank() || state.form.amount.isNotBlank() ||
            state.form.tags.isNotEmpty() || state.form.note.isNotBlank() || state.form.project.isNotBlank()
    }
    private fun draftFromSuggestion(s: PersonalMappingSuggestionDto): MappingDraft {
        val picked = s.suggestedField ?: if (s.semanticKey == "purpose") s.compatibleFields.firstOrNull { it.primary } else null
        val name = picked?.name ?: if (s.status == "conflict") "${s.standardName}（记账）" else s.standardName
        return MappingDraft(s.semanticKey, true, s.required, name, s.expectedType, picked?.id, if (picked == null) "missing" else s.status,
            (picked?.options?.takeIf { it.isNotEmpty() } ?: s.defaultOptions).joinToString("、"))
    }
    private fun validateMappingDrafts(drafts: List<MappingDraft>) {
        for (key in listOf("date", "purpose", "amount")) require(drafts.any { it.semanticKey == key && it.enabled }) { "记账日期、用途和金额必须启用" }
        val names = drafts.filter { it.enabled }.map { it.displayName.trim() }
        require(names.all { it.isNotBlank() }) { "已启用字段的名称不能为空" }
        require(names.toSet().size == names.size) { "表头名称不能重复" }
    }
    private fun semanticKeys() = listOf("date", "purpose", "amount", "paymentPlatform", "tags", "note", "project")
    private fun semanticName(key: String) = when (key) { "date" -> "记账日期"; "purpose" -> "用途"; "amount" -> "金额"; "paymentPlatform" -> "支付平台"; "tags" -> "标签"; "note" -> "备注"; else -> "归属项目" }
    private fun expectedType(key: String) = when (key) { "date" -> 5; "amount" -> 2; "paymentPlatform", "project" -> 3; "tags" -> 4; else -> 1 }
    private fun dynamicEmptyForm(config: AppConfig?): LedgerForm = emptyDynamicForm(config, displayNow())
    private fun configFromBook(book: PersonalBookDto): AppConfig {
        fun options(pattern: Regex) = book.fields.firstOrNull { pattern.containsMatchIn(it.displayName) }?.options.orEmpty()
        return AppConfig(
            paymentPlatforms = options(Regex("支付平台|支付渠道")),
            tags = options(Regex("标签|分类")),
            projects = options(Regex("归属项目|项目")),
            targetUrl = book.sourceUrl,
            bookName = book.bookName,
            tableName = book.tableName,
            fields = book.fields
        )
    }
    private fun toDisplayDate(value: String): String = runCatching { OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrElse { value.take(19).replace('T', ' ') }

    private fun launchTask(operationMessage: String? = null, block: suspend () -> Unit) {
        if (_state.value.busy) {
            _state.value = _state.value.copy(toastMessage = _state.value.operationMessage ?: "当前操作正在处理，请稍候")
            return
        }
        _state.value = _state.value.copy(
            busy = true,
            error = null,
            operationMessage = operationMessage,
            toastMessage = operationMessage ?: _state.value.toastMessage
        )
        viewModelScope.launch {
            taskMutex.withLock {
                try {
                    withTimeout(60_000) { block() }
                    if (_state.value.books.books.isNotEmpty()) {
                        settings.cachedCatalog = Json.encodeToString(_state.value.books)
                        settings.currentBookId = _state.value.books.currentBookId.orEmpty()
                    }
                } catch (e: TimeoutCancellationException) {
                    _state.value = _state.value.copy(error = "处理超时，请检查网络后重试；当前输入和截图已保留", message = null)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(loginRequired = e is ApiFailure && e.status == 401, error = e.message ?: "操作失败", message = null)
                } finally {
                    _state.value = _state.value.copy(busy = false, processingBookId = null, operationMessage = null)
                }
            }
        }
    }

    private fun launchSilentTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            taskMutex.withLock {
                try { block() } catch (e: Exception) {
                    _state.value = _state.value.copy(loginRequired = e is ApiFailure && e.status == 401, error = e.message ?: "保存失败", fieldSettingsSaving = false)
                }
            }
        }
    }

    companion object {
        fun now(): String = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        fun displayNow(): String = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}
