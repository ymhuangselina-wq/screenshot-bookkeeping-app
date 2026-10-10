package com.example.screenshotbookkeeping

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.math.BigDecimal

@Composable
fun ScreenshotBookkeepingApp(vm: MainViewModel, onAccount: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::acceptSharedImage)
    }
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.consumeToast()
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFFF47B20), secondary = Color(0xFF7A4E2D),
        background = Color(0xFFFFFBFE), surface = Color(0xFFFFFBFE),
        surfaceContainerLow = Color(0xFFFFF7F2),
        onSurface = Color(0xFF282328), onSurfaceVariant = Color(0xFF716971),
        outline = Color(0xFF8D878D), outlineVariant = Color(0xFFDED7DC)
    ), typography = AppTypography, shapes = AppShapes) {
        Scaffold(
            topBar = {
                AppBar(
                    onNew = { imagePicker.launch("image/*") },
                    onRefresh = vm::refreshFromToolbar,
                    onSettings = vm::openSettings,
                    enabled = true
                )
            },
            snackbarHost = {}
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                LedgerScreen(state, vm::updateForm, vm::save, vm::retryParse, vm::openBooks, vm::manageCurrentFields, vm::moveMainField, vm::persistMainFieldOrder, vm::restoreMainFieldOrder, vm::updateFieldOptions, vm::addFieldOption)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 20.dp)
                ) { data ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFFE2BF),
                        contentColor = Color(0xFF4B2A12),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                data.visuals.message,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        if (state.bookScreen != BookScreen.NONE) PersonalBookFlow(state, vm)
        state.optionSuggestions.entries.firstOrNull()?.let { suggestion ->
            val option = suggestion.value.firstOrNull()
            val fieldName = state.config?.fields?.find { it.fieldId == suggestion.key }?.displayName ?: "选项字段"
            if (option != null) AlertDialog(
                onDismissRequest = { vm.dismissSuggestedOption(suggestion.key, option) },
                title = { Text("发现新的选项") },
                text = { Text("字段“$fieldName”识别到新选项“$option”。是否将它新增到飞书并自动选中？") },
                confirmButton = { TextButton(onClick = { vm.addSuggestedOption(suggestion.key, option) }) { Text("新增并选中") } },
                dismissButton = { TextButton(onClick = { vm.dismissSuggestedOption(suggestion.key, option) }) { Text("忽略") } }
            )
        }
        state.switchPromptBookId?.let { bookId ->
            val targetName = state.books.books.firstOrNull { it.id == bookId }?.bookName ?: "所选账本"
            AlertDialog(
                onDismissRequest = vm::cancelSwitch,
                title = { Text("当前截图还未保存") },
                text = { Text("切换到“$targetName”后，可以使用原截图按新账本字段重新识别。") },
                confirmButton = {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = vm::reparseAndSwitch,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("切换并重新识别", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = vm::saveAndSwitch,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("保存当前账目后切换", fontWeight = FontWeight.SemiBold) }
                    }
                },
                dismissButton = {}
            )
        }
        if (state.fieldsConfirmPending) AlertDialog(
            onDismissRequest = vm::cancelFieldsConfirmation,
            title = { Text("确认同步到飞书？") },
            text = { Text(if (state.fieldLayoutDrafts.isNotEmpty()) "将保存字段顺序和必填设置，并同步到当前账本的主页面。不会修改飞书中的字段或已有数据。" else "将在飞书中重命名字段、创建新字段或更新选项。不会删除已有字段和账目内容。") },
            confirmButton = { TextButton(onClick = vm::confirmFieldChanges) { Text("确认同步") } },
            dismissButton = { TextButton(onClick = vm::cancelFieldsConfirmation) { Text("再检查一下") } }
        )
        if (state.settingsOpen) SettingsDialog(state, vm::saveSettings, vm::enterDemo, vm::closeSettings, onAccount)
        if (state.saveSucceeded) SuccessDialog(state.demoMode, state.config?.tableName, vm::dismissSuccess, vm::newManual)
    }
}

@Composable
private fun SuccessDialog(demoMode: Boolean, tableName: String?, dismiss: () -> Unit, newEntry: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (demoMode) "演示保存成功" else "记账成功") },
        text = { Text(if (demoMode) "界面操作正常。接入云端服务后，这笔记录会写入飞书多维表格。" else "这笔记录已保存到“${tableName ?: "飞书多维表格"}”。") },
        confirmButton = { TextButton(onClick = newEntry) { Text("再记一笔") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("完成") } }
    )
}

@Composable
private fun AppBar(onNew: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, enabled: Boolean) {
    Surface(modifier = Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "截图记账",
                modifier = Modifier.weight(1f),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold
            )
            CompactIconButton(onClick = onNew, enabled = enabled) { Icon(Icons.Default.Add, "新建", Modifier.size(24.dp)) }
            CompactIconButton(onClick = onRefresh, enabled = enabled) { Icon(Icons.Default.Refresh, "刷新选项", Modifier.size(22.dp)) }
            CompactIconButton(onClick = onSettings, enabled = enabled) {
                Icon(Icons.Default.Settings, "设置", Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun CompactIconButton(onClick: () -> Unit, enabled: Boolean, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun LedgerScreen(state: UiState, update: (LedgerForm) -> Unit, save: () -> Unit, retry: () -> Unit, openBooks: () -> Unit, manageFields: () -> Unit, moveField: (String, Int) -> Unit, persistOrder: () -> Unit, restoreOrder: (List<String>) -> Unit, updateOptions: (String, List<String>) -> Unit, addOption: (String, String) -> Unit) {
    val form = state.form
    val currentBook = state.books.books.find { it.id == state.books.currentBookId }
    val scrollState = rememberScrollState()
    val visibleFieldKeys = state.config?.fields.orEmpty().filter { it.fieldType in 1..5 }.map { it.fieldId }
    val reorderState = rememberReorderGroupState(scrollState, visibleFieldKeys)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saveSucceeded) {
        if (state.saveSucceeded) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    fun enabled(name: String) = state.config?.fieldNames?.let { it.isEmpty() || it.containsKey(name) } ?: true
    Column(
        Modifier
            .fillMaxSize()
            .reorderViewport(reorderState)
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
            }
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
    ) {
        if (!state.configured) {
            AssistChip(onClick = {}, label = { Text("请先配置服务地址和访问密钥") })
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前账本",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !state.busy, onClick = openBooks)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.SwapVert, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(5.dp))
                Text("切换", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .clickable(enabled = currentBook != null && !state.busy, onClick = manageFields),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, Color(0xFFEEE3DD))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    currentBook?.bookName ?: state.config?.bookName ?: "尚未设置记账表格",
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                (currentBook?.tableName ?: state.config?.tableName)?.let {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        it,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFF625B62),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.error?.let { error -> listOf("字段已变", "字段映射", "表结构", "字段不存在").any(error::contains) } == true) {
                    Spacer(Modifier.height(4.dp))
                    Text("需要修复", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
            if (state.imageUri != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = retry, enabled = !state.busy) { Text("重新识别") }
            }
            if (state.configured) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = openBooks, enabled = !state.busy) { Text("管理账本 / 修复字段") }
            }
        }
        if (state.config?.fields?.isNotEmpty() == true) {
            val visibleFields = state.config.fields.filter { it.fieldType in 1..5 }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (reorderMode) "调整字段顺序" else "记账字段",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { reorderMode = !reorderMode }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(5.dp))
                    Text(if (reorderMode) "完成" else "排序", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                visibleFields.forEach { field ->
                    key(field.fieldId) {
                        val value = form.dynamicValues[field.fieldId].orEmpty()
                        val change: (String) -> Unit = { next -> update(form.copy(dynamicValues = form.dynamicValues + (field.fieldId to next))) }
                        Box(Modifier.fillMaxWidth().reorderItem(field.fieldId, reorderState)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    when (field.fieldType) {
                                        1 -> AppTextField(value, change, field.displayName + if (field.required) " *" else "")
                                        2 -> AppTextField(value, change, field.displayName + if (field.required) " *" else "", amount = true)
                                        3 -> FeishuSelectField(field.fieldId, field.displayName + if (field.required) " *" else "", value, field.options, !field.required, false, state.operationMessage?.contains("选项") == true, change, updateOptions, addOption)
                                        4 -> FeishuSelectField(field.fieldId, field.displayName + if (field.required) " *" else "", value, field.options, !field.required, true, state.operationMessage?.contains("选项") == true, change, updateOptions, addOption)
                                        5 -> DateTimeField(value, field.displayName + if (field.required) " *" else "", false, change)
                                    }
                                }
                                if (reorderMode) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .width(40.dp)
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .reorderHandle(field.fieldId, reorderState, { moveField(field.fieldId, it) }, persistOrder, restoreOrder),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SixDotDragHandle("拖动${field.displayName}排序")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = if (state.config == null) openBooks else save,
                enabled = !state.busy && state.configured,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(17.dp)
            ) { Text(if (state.busy && state.operationMessage?.contains("写入飞书") == true) "正在记录…" else if (state.config == null) "先设置记账表格" else "记录到当前账本", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
            Text(
                "字段名称和格式来自飞书",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        Spacer(Modifier.height(22.dp))
        DateTimeField(form.date, (state.config?.fieldNames?.get("记账日期") ?: "记账日期") + " *", "date" in form.lowConfidence) { update(form.copy(date = it, lowConfidence = form.lowConfidence - "date")) }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = form.purpose, onValueChange = { update(form.copy(purpose = it, lowConfidence = form.lowConfidence - "purpose")) },
            modifier = Modifier.fillMaxWidth(), label = { Text((state.config?.fieldNames?.get("用途") ?: "用途") + " *") }, isError = "purpose" in form.lowConfidence,
            supportingText = { if ("purpose" in form.lowConfidence) Text("请确认用途") }, singleLine = true,
            shape = MaterialTheme.shapes.medium
        )
        Spacer(Modifier.height(14.dp))
        NumberCalculatorField(
            value = form.amount,
            onChange = { update(form.copy(amount = it, lowConfidence = form.lowConfidence - "amount")) },
            label = (state.config?.fieldNames?.get("金额") ?: "金额") + "（元）*"
        )
        if (enabled("支付平台")) { Spacer(Modifier.height(14.dp)); SingleSelect((state.config?.fieldNames?.get("支付平台") ?: "支付平台") + " *", form.paymentPlatform, state.config?.paymentPlatforms.orEmpty(), false) { update(form.copy(paymentPlatform = it)) } }
        if (enabled("标签")) { Spacer(Modifier.height(14.dp)); MultiSelect(state.config?.fieldNames?.get("标签") ?: "标签", form.tags, state.config?.tags.orEmpty()) { update(form.copy(tags = it)) } }
        if (enabled("备注")) { Spacer(Modifier.height(14.dp)); OutlinedTextField(value = form.note, onValueChange = { update(form.copy(note = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(state.config?.fieldNames?.get("备注") ?: "备注") }, minLines = 2, shape = MaterialTheme.shapes.medium) }
        if (enabled("归属项目")) { Spacer(Modifier.height(14.dp)); SingleSelect(state.config?.fieldNames?.get("归属项目") ?: "归属项目", form.project, state.config?.projects.orEmpty(), true) { update(form.copy(project = it)) } }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = if (state.config == null) openBooks else save,
            enabled = !state.busy && state.configured,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(17.dp)
        ) { Text(if (state.busy && state.operationMessage?.contains("写入飞书") == true) "正在记录…" else if (state.config == null) "先设置记账表格" else "记录到当前账本") }
        Spacer(Modifier.height(12.dp))
        Text("提示：支付后截图，在系统截图预览中点“分享”并选择“截图记账”。", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DateTimeField(value: String, label: String, isError: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val parsed = runCatching { OffsetDateTime.parse(value) }.getOrElse {
        runCatching { LocalDateTime.parse(value, formatter).atOffset(ZoneOffset.ofHours(8)) }.getOrElse { OffsetDateTime.now(ZoneOffset.ofHours(8)) }
    }
    val openPicker = {
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                onChange(OffsetDateTime.of(year, month + 1, day, hour, minute, 0, 0, ZoneOffset.ofHours(8)).format(formatter))
            }, parsed.hour, parsed.minute, true).show()
        }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).show()
    }
    CompactReadOnlyField(
        value = value,
        label = label,
        isError = isError,
        onClick = openPicker,
        trailing = { Text("选择", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
    )
}

@Composable
private fun AppTextField(value: String, onChange: (String) -> Unit, label: String, amount: Boolean = false) {
    if (amount) {
        NumberCalculatorField(value = value, onChange = onChange, label = label)
        return
    }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(13.dp)
    val containerColor = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxWidth()
            .height(AppFieldHeight)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(containerColor, shape)
                .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = if (amount) FontWeight.Bold else FontWeight.Normal
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (amount) KeyboardType.Decimal else KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(force = true) }),
                interactionSource = interactionSource,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() }
                }
            )
        }
        FloatingFieldLabel(label, floating = focused || value.isNotBlank(), active = focused)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberCalculatorField(value: String, onChange: (String) -> Unit, label: String) {
    var open by remember { mutableStateOf(false) }
    CompactReadOnlyField(
        value = value,
        label = label,
        placeholder = "0",
        labelFloated = open || value.isNotBlank(),
        active = open,
        onClick = { open = true },
        trailing = {
            Icon(
                Icons.Default.Calculate,
                contentDescription = "打开计算器",
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    if (open) NumberCalculatorSheet(
        label = label.removeSuffix(" *"),
        initialValue = value,
        onDismiss = { open = false },
        onConfirm = {
            onChange(it)
            open = false
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberCalculatorSheet(
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var expression by remember(initialValue) { mutableStateOf(initialValue.ifBlank { "0" }) }
    var invalid by remember { mutableStateOf(false) }
    val operators = setOf('+', '-', '×', '÷')
    val hasCalculation = expression.drop(1).any { it in operators }
    val previewResult = remember(expression) {
        evaluateSimpleExpression(expression)?.let {
            BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()
        }
    }
    fun press(key: String) {
        invalid = false
        when (key) {
            "clear" -> expression = "0"
            "back" -> expression = expression.dropLast(1).ifBlank { "0" }
            "." -> {
                val current = expression.split('+', '-', '×', '÷').last()
                if ('.' !in current) expression += if (current.isBlank()) "0." else "."
            }
            "+", "-", "×", "÷" -> {
                expression = if (expression.lastOrNull() in operators) expression.dropLast(1) + key else expression + key
            }
            else -> expression = if (expression == "0") key else expression + key
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = Color(0xFFF7F3F5),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(34.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp)))
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 18.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (hasCalculation) expression else label,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        fontSize = if (hasCalculation) 20.sp else 12.sp,
                        lineHeight = if (hasCalculation) 26.sp else 18.sp,
                        fontWeight = if (hasCalculation) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (hasCalculation) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (hasCalculation) previewResult ?: "—" else expression,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (invalid) Text("请完成当前计算", modifier = Modifier.padding(start = 4.dp, top = 6.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            val rows = listOf(
                listOf("7", "8", "9", "÷", "back"),
                listOf("4", "5", "6", "×", "clear"),
                listOf("1", "2", "3", "-", "done"),
                listOf(".", "0", "00", "+")
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { key ->
                            val isDone = key == "done"
                            val background = when {
                                isDone -> MaterialTheme.colorScheme.primary
                                key == "clear" || key == "back" -> Color(0xFFFFEEE9)
                                key.singleOrNull() in operators -> Color(0xFFEEE8EC)
                                else -> Color.White
                            }
                            val foreground = when {
                                isDone -> Color.White
                                key == "clear" || key == "back" -> Color(0xFFC44325)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Surface(
                                onClick = {
                                    if (isDone) {
                                        val result = evaluateSimpleExpression(expression)
                                        if (result == null) invalid = true
                                        else onConfirm(BigDecimal.valueOf(result).stripTrailingZeros().toPlainString())
                                    } else press(key)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (isDone && rowIndex == 2) 54.dp else 54.dp),
                                shape = RoundedCornerShape(13.dp),
                                color = background,
                                contentColor = foreground,
                                shadowElevation = if (isDone) 0.dp else 1.dp
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    when (key) {
                                        "back" -> Icon(Icons.Default.Backspace, "退格", Modifier.size(22.dp))
                                        "clear" -> Text("C", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        "done" -> Text("完成", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        else -> Text(key, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        if (rowIndex == 3) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun evaluateSimpleExpression(raw: String): Double? {
    val expression = raw.replace('×', '*').replace('÷', '/')
    if (expression.isBlank()) return null
    return runCatching {
        val values = mutableListOf<Double>()
        val lowPriority = mutableListOf<Char>()
        var index = 0
        var current = StringBuilder()
        var pendingHigh: Char? = null
        fun pushNumber() {
            val number = current.toString().toDouble()
            current = StringBuilder()
            if (pendingHigh == null) values += number
            else {
                val left = values.removeAt(values.lastIndex)
                values += if (pendingHigh == '*') left * number else left / number
                pendingHigh = null
            }
        }
        while (index < expression.length) {
            val char = expression[index]
            if (char.isDigit() || char == '.' || (char == '-' && index == 0)) current.append(char)
            else {
                pushNumber()
                if (char == '*' || char == '/') pendingHigh = char else lowPriority += char
            }
            index++
        }
        pushNumber()
        var result = values.first()
        lowPriority.forEachIndexed { operatorIndex, operator ->
            result = if (operator == '+') result + values[operatorIndex + 1] else result - values[operatorIndex + 1]
        }
        require(result.isFinite())
        result
    }.getOrNull()
}

@Composable
private fun CompactReadOnlyField(
    value: String,
    label: String,
    placeholder: String = "",
    labelFloated: Boolean = value.isNotBlank(),
    active: Boolean = false,
    isError: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(AppFieldHeight)
            .clickable {
                focusManager.clearFocus(force = true)
                onClick()
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(
                    1.dp,
                    when {
                        isError -> MaterialTheme.colorScheme.error
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape
                )
        ) {
            Row(
                Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        value.isNotBlank() -> value
                        labelFloated -> placeholder
                        else -> ""
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = if (value.isBlank()) 14.sp else 16.sp,
                    lineHeight = if (value.isBlank()) 20.sp else 24.sp,
                    color = if (value.isBlank()) Color(0xFFAAA2AA) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                trailing?.invoke()
            }
        }
        FloatingFieldLabel(label, floating = labelFloated, active = active)
    }
}

@Composable
private fun BoxScope.FloatingFieldLabel(label: String, floating: Boolean = true, active: Boolean = false) {
    val y by animateDpAsState(if (floating) (-8).dp else 18.dp, label = "field-label-y")
    val fontSize by animateFloatAsState(if (floating) 12f else 16f, label = "field-label-size")
    val labelColor by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "field-label-color"
    )
    Text(
        label,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = 12.dp, y = y)
            .then(if (floating) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
            .padding(horizontal = 6.dp),
        color = labelColor,
        fontSize = fontSize.sp,
        lineHeight = if (floating) 16.sp else 22.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleSelect(label: String, value: String, options: List<String>, allowEmpty: Boolean, onChange: (String) -> Unit) {
    ChoiceChips(label, options, if (value.isBlank()) emptySet() else setOf(value), allowEmpty, false) { picked -> onChange(picked.firstOrNull().orEmpty()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelect(label: String, selected: Set<String>, options: List<String>, onChange: (Set<String>) -> Unit) {
    ChoiceChips(label, options, selected, false, true, onChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeishuSelectField(
    fieldId: String,
    label: String,
    value: String,
    options: List<String>,
    allowEmpty: Boolean,
    multiple: Boolean,
    syncing: Boolean,
    onChange: (String) -> Unit,
    updateOptions: (String, List<String>) -> Unit,
    addOption: (String, String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = value.split("、").filter { it.isNotBlank() }.toSet()
    val context = LocalContext.current
    val preferences = remember(context) { SecureSettings(context) }
    val recent = preferences.recentOptions(fieldId)
    val orderedOptions = (recent.filter { it in options } + options).distinct()
    CompactReadOnlyField(
        value = value,
        label = label,
        placeholder = if (options.isEmpty()) "飞书中暂无选项" else "请选择",
        labelFloated = open || value.isNotBlank(),
        active = open,
        onClick = { open = true },
        trailing = {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    if (open) FeishuOptionSheet(
        title = label.removeSuffix(" *"), options = orderedOptions, selected = selected,
        allowEmpty = allowEmpty, multiple = multiple, syncing = syncing,
        onDismiss = { open = false },
        onSelected = { values ->
            onChange(values.joinToString("、"))
            if (!multiple) open = false
        },
        onOptionsChanged = { updateOptions(fieldId, it) },
        onAddOption = { addOption(fieldId, it) }
    )
}

@Composable
internal fun SixDotDragHandle(contentDescription: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    Canvas(
        Modifier
            .size(width = 12.dp, height = 20.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        val radius = 1.25.dp.toPx()
        val left = size.width * 0.30f
        val right = size.width * 0.70f
        for (y in listOf(size.height * 0.22f, size.height * 0.50f, size.height * 0.78f)) {
            drawCircle(color, radius, Offset(left, y))
            drawCircle(color, radius, Offset(right, y))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeishuOptionSheet(
    title: String,
    options: List<String>,
    selected: Set<String>,
    allowEmpty: Boolean,
    multiple: Boolean,
    syncing: Boolean,
    onDismiss: () -> Unit,
    onSelected: (Set<String>) -> Unit,
    onOptionsChanged: (List<String>) -> Unit,
    onAddOption: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var createHint by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var listReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        listReady = true
    }
    val filtered = remember(options, query) {
        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
    }
    val choose: (String) -> Unit = { option ->
        onSelected(if (multiple) {
            if (option in selected) selected - option else selected + option
        } else setOf(option))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 34.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp))
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Row(
                Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.width(80.dp).height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("取消", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Text(
                    title,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.width(80.dp).height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("完成", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }

            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (createHint) 4.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val searchShape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Color.White, searchShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, searchShape)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it; createHint = false },
                            modifier = Modifier.weight(1f).focusRequester(searchFocus),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) Text("查找选项", fontSize = 15.sp, color = Color(0xFF9A9299))
                                    inner()
                                }
                            }
                        )
                    }
                    Surface(
                        onClick = {
                            val clean = query.trim()
                            when {
                                clean.isEmpty() -> { createHint = true; searchFocus.requestFocus() }
                                clean in options -> { choose(clean); query = "" }
                                else -> { onAddOption(clean); query = "" }
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        enabled = !syncing,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Add, contentDescription = "新建选项", modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (syncing) {
                    Text(
                        "正在同步到飞书，完成后会自动选中…",
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                if (createHint) {
                    Text(
                        "请先输入选项名称",
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .9f))
            }

            if (!listReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 36.dp).size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        "没有找到匹配的选项",
                        Modifier.padding(top = 64.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it }) { option ->
                        val checked = option in selected
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else MaterialTheme.colorScheme.surface)
                                .clickable { choose(option) }
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(start = 20.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(42.dp), contentAlignment = Alignment.CenterStart) {
                                    if (multiple) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { choose(option) },
                                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                        )
                                    } else {
                                        RadioButton(
                                            selected = checked,
                                            onClick = { choose(option) },
                                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                                Text(
                                    option,
                                    Modifier.weight(1f),
                                    fontSize = 16.sp,
                                    lineHeight = 23.sp,
                                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (checked) Color(0xFF7F3B0D) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box {
                                    IconButton(onClick = { menuFor = option }, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.MoreVert, "管理选项 $option", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (menuFor == option) {
                                        DropdownMenu(true, { menuFor = null }, shape = RoundedCornerShape(12.dp)) {
                                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menuFor = null; editing = option })
                                            DropdownMenuItem(
                                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                onClick = { menuFor = null; deleting = option }
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .9f)
                            )
                        }
                    }
                }
            }
        }
    }
    editing?.let { original ->
        var renamed by remember(original) { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("重命名选项") },
            text = { OutlinedTextField(renamed, { renamed = it }, singleLine = true, label = { Text("选项名称") }) },
            confirmButton = { TextButton(onClick = {
                val clean = renamed.trim()
                if (clean.isNotEmpty() && (clean == original || clean !in options)) {
                    onOptionsChanged(options.map { if (it == original) clean else it })
                    if (original in selected) onSelected(selected - original + clean)
                    editing = null
                }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
    deleting?.let { option ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除“$option”？") },
            text = { Text("该选项会从 APP 的可选列表中移除；飞书中的选项和历史记录保持不变。") },
            confirmButton = { TextButton(onClick = {
                onOptionsChanged(options - option)
                if (option in selected) onSelected(selected - option)
                deleting = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChips(
    label: String,
    options: List<String>,
    selected: Set<String>,
    allowEmpty: Boolean,
    multiple: Boolean,
    onChange: (Set<String>) -> Unit
) {
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val compact = options.size <= 8 && options.none { it.length > 12 }
    val visible = if (compact) options else options.take(5)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (options.isEmpty()) {
            Text("飞书中暂无选项", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (allowEmpty) ChoiceChip("不选择", selected.isEmpty()) { onChange(emptySet()) }
            visible.forEach { option ->
                ChoiceChip(option, option in selected) {
                    onChange(if (multiple) if (option in selected) selected - option else selected + option else setOf(option))
                }
            }
            if (!compact) AssistChip(onClick = { query = ""; showAll = true }, label = { Text("更多 +${options.size - visible.size}") })
        }
    }
    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label.removeSuffix(" *"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索选项") }, singleLine = true)
            val filtered = options.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) Text("没有匹配的选项", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (allowEmpty) ChoiceChip("不选择", selected.isEmpty()) { onChange(emptySet()); if (!multiple) showAll = false }
                filtered.forEach { option -> ChoiceChip(option, option in selected) {
                    onChange(if (multiple) if (option in selected) selected - option else selected + option else setOf(option))
                    if (!multiple) showAll = false
                } }
            }
            if (multiple) Button(onClick = { showAll = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) ({ Icon(Icons.Default.CheckCircle, null, Modifier.size(17.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .14f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun LegacySettingsDialog(
    state: UiState,
    save: (String, String, String) -> Unit,
    rename: (Map<String, String>) -> Unit,
    demo: () -> Unit,
    dismiss: () -> Unit
) {
    val uri = LocalUriHandler.current
    val semantics = listOf("记账日期", "用途", "金额", "支付平台", "标签", "备注", "归属项目")
    var url by remember(state.settingsOpen) { mutableStateOf(state.workerUrl) }
    var key by remember(state.settingsOpen) { mutableStateOf("") }
    var targetUrl by remember(state.settingsOpen) { mutableStateOf("") }
    var advanced by remember(state.settingsOpen) { mutableStateOf(!state.configured) }
    var switching by remember(state.settingsOpen) { mutableStateOf(!state.configured) }
    var editingFields by remember(state.settingsOpen) { mutableStateOf(false) }
    var confirmRename by remember { mutableStateOf(false) }
    var names by remember(state.settingsOpen, state.config) {
        mutableStateOf(semantics.associateWith { state.config?.fieldNames?.get(it) ?: it })
    }
    AlertDialog(
        onDismissRequest = { if (state.configured && !state.busy) dismiss() },
        title = { Text(if (state.configured) "账本设置" else "首次服务设置") },
        text = { Column(
            Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.configured) {
                Text("当前记账位置", style = MaterialTheme.typography.labelLarge)
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.config?.bookName ?: "飞书多维表格", style = MaterialTheme.typography.titleSmall)
                    Text(state.config?.tableName ?: "未知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        state.config?.targetUrl?.let { link -> TextButton(onClick = { uri.openUri(link) }) { Text("打开飞书查看/修改") } }
                        TextButton(onClick = { switching = !switching }) { Text(if (switching) "取消切换" else "切换表格") }
                    }
                } }
                if (switching) {
                    OutlinedTextField(targetUrl, { targetUrl = it }, label = { Text("新的多维表格链接") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    Text("切换前会校验 7 个表头，原表不会被删除。", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("表头管理", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { editingFields = !editingFields }) { Text(if (editingFields) "取消" else "修改表头") }
                }
                if (editingFields) {
                    Text("修改后会同步重命名飞书字段，不会修改已有账目内容。", style = MaterialTheme.typography.bodySmall)
                    semantics.forEach { semantic ->
                        OutlinedTextField(names[semantic].orEmpty(), { value -> names = names + (semantic to value) }, label = { Text("原定义：$semantic") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    }
                    Button(onClick = { confirmRename = true }, enabled = names.values.all { it.isNotBlank() } && names.values.toSet().size == semantics.size, modifier = Modifier.fillMaxWidth()) { Text("预览并确认修改") }
                }
                HorizontalDivider()
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级服务设置" else "高级服务设置") }
            if (advanced) {
                Text("一般无需修改以下内容。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("服务 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                Text(
                    buildString {
                        append("实际服务：")
                        append(serviceHost(url))
                        state.serviceBuild?.let { append("  ·  build ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(key, { key = it }, label = { Text(if (state.configured) "个人访问密钥（留空不修改）" else "个人访问密钥") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            }
            if (!state.configured) OutlinedTextField(targetUrl, { targetUrl = it }, label = { Text("飞书多维表格链接（可稍后填写）") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = {
            when {
                !state.configured -> TextButton(onClick = { save(url, key, targetUrl) }, enabled = !state.busy && url.startsWith("https://") && key.isNotBlank()) { Text("保存并连接") }
                switching -> TextButton(onClick = { save(url, key, targetUrl) }, enabled = !state.busy && targetUrl.startsWith("http")) { Text("验证并切换") }
                advanced -> TextButton(onClick = { save(url, key, "") }, enabled = !state.busy && url.startsWith("https://")) { Text("保存服务设置") }
                else -> TextButton(onClick = dismiss) { Text("完成") }
            }
        },
        dismissButton = { if (state.configured) TextButton(onClick = dismiss) { Text("关闭") } else TextButton(onClick = demo) { Text("先体验演示") } }
    )
    if (confirmRename) AlertDialog(
        onDismissRequest = { confirmRename = false },
        title = { Text("确认修改飞书表头？") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            semantics.forEach { semantic ->
                val before = state.config?.fieldNames?.get(semantic) ?: semantic
                val after = names[semantic].orEmpty()
                Text(if (before == after) before else "$before  →  $after")
            }
            Spacer(Modifier.height(6.dp)); Text("这会立即重命名多维表格中的字段，已有账目内容不会改变。")
        } },
        confirmButton = { TextButton(onClick = { confirmRename = false; editingFields = false; rename(names) }) { Text("确认修改") } },
        dismissButton = { TextButton(onClick = { confirmRename = false }) { Text("再检查一下") } }
    )
}

private enum class SettingsPage { ROOT, NOTIFICATION, PAGE_SHORTCUT, SHARE_SCREENSHOT, SERVICE }

@Composable
private fun SettingsDialog(state: UiState, save: (String, String, String) -> Unit, demo: () -> Unit, dismiss: () -> Unit, onAccount: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var page by remember(state.settingsOpen) { mutableStateOf(SettingsPage.ROOT) }
    var url by remember(state.settingsOpen) { mutableStateOf(state.workerUrl) }
    val maskedKey = "••••••••••••"
    var key by remember(state.settingsOpen) { mutableStateOf(if (state.configured) maskedKey else "") }
    var keyChanged by remember(state.settingsOpen) { mutableStateOf(false) }
    var listenerEnabled by remember { mutableStateOf(PaymentNotificationBridge.listenerEnabled(context) && PaymentNotificationBridge.promptsEnabled(context)) }
    var pageShortcutEnabled by remember { mutableStateOf(PaymentPageAccessibilityService.enabled(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    fun back() { if (page == SettingsPage.ROOT) dismiss() else page = SettingsPage.ROOT }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = PaymentNotificationBridge.listenerEnabled(context) && PaymentNotificationBridge.promptsEnabled(context)
                pageShortcutEnabled = PaymentPageAccessibilityService.enabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = ::back)
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::back, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ArrowBack, "返回", Modifier.size(24.dp))
                    }
                    Text(
                        when (page) {
                            SettingsPage.ROOT -> "设置"
                            SettingsPage.NOTIFICATION -> "系统通知提醒"
                            SettingsPage.PAGE_SHORTCUT -> "页面智能监测"
                            SettingsPage.SHARE_SCREENSHOT -> "截图分享识别"
                            SettingsPage.SERVICE -> "服务连接"
                        },
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    )
                }
                when (page) {
                    SettingsPage.ROOT -> SettingsRoot(
                        state = state,
                        listenerEnabled = listenerEnabled,
                        pageShortcutEnabled = pageShortcutEnabled,
                        onPage = { page = it }
                    )
                    SettingsPage.NOTIFICATION -> NotificationSettingsDetail(
                        enabled = listenerEnabled,
                        onOpenPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (PaymentNotificationBridge.listenerEnabled(context) && !PaymentNotificationBridge.promptsEnabled(context)) {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            } else context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                    SettingsPage.PAGE_SHORTCUT -> PageShortcutSettingsDetail(
                        enabled = pageShortcutEnabled,
                        onOpenPermission = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                    SettingsPage.SHARE_SCREENSHOT -> ShareScreenshotSettingsDetail()
                    SettingsPage.SERVICE -> ServiceSettingsDetail(
                        state = state,
                        url = url,
                        key = key,
                        keyChanged = keyChanged,
                        onUrl = { url = it },
                        onKey = { value -> key = if (!keyChanged) value.replace("•", "") else value; keyChanged = true },
                        onSave = { save(url, if (keyChanged) key else "", "") },
                        onDemo = demo
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRoot(state: UiState, listenerEnabled: Boolean, pageShortcutEnabled: Boolean, onPage: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("设置快捷记账方式和账本连接。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        SettingsSectionTitle("快捷记账")
        SettingsListRow(Icons.Default.TouchApp, "页面智能监测", "在支付或订单页点击浮动按钮，截屏并识别", if (pageShortcutEnabled) "已开启" else "未开启", pageShortcutEnabled) { onPage(SettingsPage.PAGE_SHORTCUT) }
        SettingsListRow(Icons.Default.NotificationsNone, "系统通知提醒", "收到支付或转账通知后，点击提醒填入账目", if (listenerEnabled) "已开启" else "未开启", listenerEnabled) { onPage(SettingsPage.NOTIFICATION) }
        SettingsListRow(Icons.Default.Share, "截图分享识别", "截图后从系统分享面板发送到“截图记账”", "已开启", true) { onPage(SettingsPage.SHARE_SCREENSHOT) }
        Spacer(Modifier.height(28.dp))
        SettingsSectionTitle("账本与服务")
        SettingsListRow(Icons.Default.CloudDone, "服务连接", "查看或修改识别服务地址和个人访问密钥", null, false) { onPage(SettingsPage.SERVICE) }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsListRow(icon: ImageVector, title: String, subtitle: String, status: String?, positive: Boolean, showChevron: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp).clickable(enabled = showChevron, onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = RoundedCornerShape(11.dp), color = Color(0xFFF4EEF1), contentColor = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        }
        status?.let { Text(it, color = if (positive) Color(0xFF2E6B43) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = if (positive) FontWeight.SemiBold else FontWeight.Normal) }
        if (showChevron) Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun NotificationSettingsDetail(enabled: Boolean, onOpenPermission: () -> Unit) {
    SettingsDetailBody(
        title = if (enabled) "已开启" else "支付完成，少填几项",
        description = if (enabled) "下次识别到支付或转账通知后，你会收到记账提醒。" else "在本机识别支付宝、微信、云闪付和常见银行的支付或转账通知。",
        notes = listOf("支持的通知" to "支付、转账、银行卡消费", "填入方式" to "打开表单后确认", "隐私" to "只在本机筛选通知内容"),
        action = if (enabled) "关闭该记账方式" else "开启系统通知提醒",
        onAction = onOpenPermission
    )
}

@Composable
private fun PageShortcutSettingsDetail(enabled: Boolean, onOpenPermission: () -> Unit) {
    SettingsDetailBody(
        title = if (enabled) "已开启" else "支付页上直接记账",
        description = "识别到支付、转账或订单详情页时，右下角会出现“截图记账”。",
        notes = listOf("截屏时机" to "只有点击按钮后", "使用范围" to "支付宝、微信、云闪付及常见银行", "系统权限" to if (enabled) "已允许" else "需要手动开启无障碍权限"),
        guide = if (enabled) emptyList() else listOf(
            "点击下方“前往系统设置”",
            "在“辅助功能”页面点击“已下载的应用”",
            "选择“截图记账-自动记账”，开启页面顶部的“使用”",
            "保持下方的“快捷方式”关闭，它是系统服务开关，不是记账按钮"
        ),
        action = if (enabled) "关闭该记账方式" else "前往系统设置",
        onAction = onOpenPermission
    )
}

@Composable
private fun ShareScreenshotSettingsDetail() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("不用额外开启权限", fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "适合所有能手动截图的支付、订单和账单页面。分享后会直接打开截图记账并开始识别。",
            Modifier.padding(top = 8.dp, bottom = 22.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("使用方法", fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                listOf(
                    "在支付、订单或账单页面截图",
                    "点击系统截图预览里的“分享”",
                    "选择“截图记账”，等待自动识别"
                ).forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF0E9ED), modifier = Modifier.size(26.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                        Text(step, Modifier.weight(1f).padding(top = 3.dp), fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
        Text("如果支付通知或页面快捷按钮没有出现，可以用这种方式兜底。", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun SettingsDetailBody(title: String, description: String, notes: List<Pair<String, String>>, guide: List<String> = emptyList(), action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        Text(description, Modifier.padding(top = 8.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp)
        notes.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(value, Modifier.widthIn(max = 220.dp), fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (guide.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("开启方法", fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                    guide.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF0E9ED), modifier = Modifier.size(26.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                            Text(step, Modifier.weight(1f).padding(top = 3.dp), fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                    Text("不需要开启“无障碍功能菜单”或“快捷方式”。真正的“截图记账”按钮只会在支付或订单页面出现。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text(action, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ServiceSettingsDetail(state: UiState, url: String, key: String, keyChanged: Boolean, onUrl: (String) -> Unit, onKey: (String) -> Unit, onSave: () -> Unit, onDemo: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (state.configured) "服务连接正常" else "连接记账服务", fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Text("一般无需修改以下内容。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        OutlinedTextField(url, onUrl, label = { Text("服务 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        OutlinedTextField(key, onKey, label = { Text("个人访问密钥") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(onClick = onSave, enabled = !state.busy && url.startsWith("https://") && (state.configured || key.isNotBlank() || keyChanged), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text("保存服务设置", fontWeight = FontWeight.Bold) }
        if (!state.configured) TextButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Text("先体验演示") }
    }
}

@Preview(name = "截图记账确认页", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LedgerScreenPreview() {
    val state = UiState(
        configured = true,
        config = AppConfig(
            paymentPlatforms = listOf("支付宝", "微信支付", "银行卡"),
            tags = listOf("餐饮", "交通", "办公", "差旅"),
            projects = listOf("日常生活", "差旅报销")
        ),
        form = LedgerForm(
            date = "2026-09-15T13:26:00+08:00",
            purpose = "瑞幸咖啡 · 生椰拿铁",
            amount = "18.00",
            paymentPlatform = "支付宝",
            tags = setOf("餐饮"),
            note = "上海静安寺店",
            project = "日常生活"
        ),
        message = "AI 已识别支付结果，请确认后保存"
    )
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFF47B20), secondary = Color(0xFF7A4E2D))) {
        LedgerScreen(state = state, update = {}, save = {}, retry = {}, openBooks = {}, manageFields = {}, moveField = { _, _ -> }, persistOrder = {}, restoreOrder = {}, updateOptions = { _, _ -> }, addOption = { _, _ -> })
    }
}
