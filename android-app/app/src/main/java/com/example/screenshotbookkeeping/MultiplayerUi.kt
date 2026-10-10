package com.example.screenshotbookkeeping

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Composable
fun MultiplayerApp(vm: MultiplayerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFF47B20), onPrimary = androidx.compose.ui.graphics.Color(0xFF282328)), typography = AppTypography, shapes = AppShapes) {
        Box(Modifier.fillMaxSize()) {
            when (state.screen) {
                AppScreen.WELCOME -> WelcomeScreen(state, vm::showLogin)
                AppScreen.LOGIN -> LoginScreen(state, vm)
                AppScreen.BOOK_CHOICE -> BookChoiceScreen(state, vm::chooseConnect, vm::chooseCreate, vm::showSettings)
                AppScreen.CONNECT -> ConnectScreen(state, vm::setTableUrl, vm::inspect, vm::backToLedger)
                AppScreen.MAP_FIELDS -> MappingScreen(state, vm::updateMapping, vm::connectBook, vm::chooseConnect)
                AppScreen.CREATE_BOOK -> CreateBookScreen(state, vm::updateNewBookName, vm::updateNewTableName, vm::updateMapping, vm::createBook, vm::backToLedger)
                AppScreen.LEDGER -> LedgerV2Screen(state, vm::updateForm, vm::save, vm::retryParse, vm::newManual, vm::showSettings)
                AppScreen.SETTINGS -> SettingsV2Screen(state, vm::backToLedger, vm::refreshBook, vm::chooseConnect, vm::chooseCreate, vm::logout, vm::askDelete)
            }
            if (state.busy) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .76f), modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("正在处理…") } }
            }
        }
        if (state.saveSucceeded) AlertDialog(
            onDismissRequest = vm::dismissSuccess,
            title = { Text("记账成功") }, text = { Text("这笔记录已保存到“${state.me?.book?.name.orEmpty()}”。") },
            confirmButton = { TextButton(onClick = vm::newManual) { Text("再记一笔") } }, dismissButton = { TextButton(onClick = vm::dismissSuccess) { Text("完成") } }
        )
        if (state.deleteConfirm) AlertDialog(
            onDismissRequest = vm::cancelDelete, title = { Text("注销账户？") },
            text = { Text("将删除服务端授权和账本设置，但不会删除飞书中的表格和记录。") },
            confirmButton = { TextButton(onClick = vm::deleteAccount) { Text("确认注销", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("取消") } }
        )
    }
}

@Composable private fun Page(title: String, state: MultiplayerState, back: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { @OptIn(ExperimentalMaterial3Api::class) TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { if (back != null) IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "返回") } }
    ) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            content()
        })
    }
}

@Composable private fun WelcomeScreen(state: MultiplayerState, next: () -> Unit) = Page("截图记账", state) {
    Spacer(Modifier.height(24.dp)); Text("支付截图，一键记入你的飞书账本", style = MaterialTheme.typography.headlineSmall)
    Text("截图经记账后台发送给阿里云百炼识别。确认后的账目写入你配置的飞书表格，原图不作为账本附件保存。")
    PrivacyLink()
    Button(onClick = next, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("开始使用") }
}

@Composable private fun LoginScreen(state: MultiplayerState, vm: MultiplayerViewModel) {
    Page(if (state.registering) "创建截图记账账号" else "登录截图记账", state, vm::backToWelcome) {
        Text(if (state.registering) "用邀请码创建独立账号，之后用账号和密码登录。" else "使用你的截图记账账号登录。无需飞书账号授权。")
        OutlinedTextField(state.username, vm::setUsername, label = { Text("账号") }, supportingText = { Text("3–40 位字母、数字、点、横线或下划线") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.password, vm::setPassword, label = { Text("密码") }, supportingText = { Text("至少 10 位字符") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (state.registering) OutlinedTextField(state.inviteCode, vm::setInvite, label = { Text("一次性邀请码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.privacyAccepted, vm::setPrivacyAccepted)
            Text("我已阅读并同意", style = MaterialTheme.typography.bodySmall)
            PrivacyLink()
        }
        Button(onClick = vm::signIn, enabled = !state.busy && state.privacyAccepted && state.username.isNotBlank() && state.password.length >= 10 && (!state.registering || state.inviteCode.isNotBlank()), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (state.registering) "注册并继续" else "登录") }
        TextButton(onClick = { vm.setRegistering(!state.registering) }, modifier = Modifier.fillMaxWidth()) { Text(if (state.registering) "已有账号，去登录" else "有邀请码，创建账号") }
        Text("请妥善保存账号和密码。邀请测试期间尚未提供自助找回密码。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun BookChoiceScreen(state: MultiplayerState, connect: () -> Unit, create: () -> Unit, settings: () -> Unit) = Page("设置我的账本", state) {
    Text("你好，${state.me?.user?.name.orEmpty()}")
    ElevatedCard(onClick = connect, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("连接已有多维表格", fontWeight = FontWeight.Bold); Text("粘贴链接，自动识别并补齐记账字段") } }
    ElevatedCard(onClick = create, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("创建新的记账表格", fontWeight = FontWeight.Bold); Text("选择字段和选项，自动在飞书中建好账本") } }
    TextButton(onClick = settings) { Text("账户设置") }
}

@Composable private fun ConnectScreen(state: MultiplayerState, update: (String) -> Unit, inspect: (String?) -> Unit, back: () -> Unit) = Page("连接已有表格", state, back) {
    Text("在飞书中打开多维表格，复制浏览器或分享链接后粘贴到下方。")
    OutlinedTextField(state.tableUrl, update, label = { Text("飞书多维表格链接") }, minLines = 3, modifier = Modifier.fillMaxWidth())
    Button(onClick = { inspect(null) }, enabled = state.tableUrl.startsWith("http"), modifier = Modifier.fillMaxWidth()) { Text("读取表格") }
    state.inspection?.takeIf { it.selectionRequired }?.let { result ->
        Text("该多维表格包含多个数据表：", fontWeight = FontWeight.Bold)
        result.tables.forEach { table -> OutlinedButton(onClick = { inspect(table.table_id) }, modifier = Modifier.fillMaxWidth()) { Text(table.name) } }
    }
}

@Composable private fun MappingScreen(state: MultiplayerState, update: (Int, (MappingDraft) -> MappingDraft) -> Unit, save: () -> Unit, back: () -> Unit) = Page("确认字段", state, back) {
    Text("我们不会删除或覆盖已有字段。缺少的字段将在你确认后创建。")
    FieldEditors(state, update, allowExisting = true)
    Button(onClick = save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("确认并配置") }
}

@Composable private fun CreateBookScreen(state: MultiplayerState, setName: (String) -> Unit, setTable: (String) -> Unit, update: (Int, (MappingDraft) -> MappingDraft) -> Unit, save: () -> Unit, back: () -> Unit) = Page("创建新账本", state, back) {
    OutlinedTextField(state.newBookName, setName, label = { Text("多维表格名称") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state.newTableName, setTable, label = { Text("数据表名称") }, modifier = Modifier.fillMaxWidth())
    FieldEditors(state, update, allowExisting = false)
    Button(onClick = save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("创建账本") }
}

@Composable private fun FieldEditors(state: MultiplayerState, update: (Int, (MappingDraft) -> MappingDraft) -> Unit, allowExisting: Boolean) {
    state.mappings.forEachIndexed { index, mapping ->
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(semanticLabel(mapping.semanticKey), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (mapping.required) Text("必选", color = MaterialTheme.colorScheme.primary)
                else Switch(mapping.enabled, { checked -> update(index) { it.copy(enabled = checked) } })
            }
            if (mapping.enabled) {
                if (allowExisting) ExistingFieldPicker(state.inspection?.fields.orEmpty().filter { it.type == mapping.expectedType }, mapping) { field ->
                    update(index) { it.copy(fieldId = field?.id, displayName = field?.name ?: it.displayName, optionsText = field?.options?.joinToString("、") ?: it.optionsText, status = if (field == null) "missing" else "matched") }
                }
                OutlinedTextField(mapping.displayName, { value -> update(index) { it.copy(displayName = value) } }, label = { Text(if (mapping.fieldId == null) "将创建的字段名" else "已匹配字段") }, enabled = mapping.fieldId == null, modifier = Modifier.fillMaxWidth())
                if (mapping.expectedType == 3 || mapping.expectedType == 4) OutlinedTextField(mapping.optionsText, { value -> update(index) { it.copy(optionsText = value) } }, label = { Text("选项，用顿号分隔") }, enabled = mapping.fieldId == null, modifier = Modifier.fillMaxWidth())
                Text(when { mapping.fieldId != null -> "已匹配现有字段"; mapping.status == "conflict" -> "同名字段类型冲突，将新建字段"; else -> "将自动创建" }, style = MaterialTheme.typography.bodySmall)
            }
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExistingFieldPicker(fields: List<FeishuFieldDto>, mapping: MappingDraft, choose: (FeishuFieldDto?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(mapping.fieldId?.let { id -> fields.find { it.id == id }?.name } ?: "新建字段", {}, readOnly = true,
            label = { Text("对应的飞书字段") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            fields.forEach { field -> DropdownMenuItem({ Text(field.name) }, { choose(field); expanded = false }) }
            DropdownMenuItem({ Text("新建字段") }, { choose(null); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LedgerV2Screen(state: MultiplayerState, update: (LedgerForm) -> Unit, save: () -> Unit, retry: () -> Unit, fresh: () -> Unit, settings: () -> Unit) {
    val form = state.form
    val mappings = state.me?.book?.mappings.orEmpty().associateBy { it.semanticKey }
    Scaffold(topBar = { TopAppBar(title = { Text("截图记账", fontWeight = FontWeight.Bold) }, actions = {
        Text("${state.me?.quota?.remaining ?: 0}/${state.me?.quota?.limit ?: 20}", style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = settings) { Icon(Icons.Default.Settings, "设置") }
    }) }) { padding -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); if (state.imageUri != null) OutlinedButton(onClick = retry) { Text("重新识别") } }
        DateFieldV2(form.date, "date" in form.lowConfidence) { update(form.copy(date = it, lowConfidence = form.lowConfidence - "date")) }
        OutlinedTextField(form.purpose, { update(form.copy(purpose = it, lowConfidence = form.lowConfidence - "purpose")) }, label = { Text(mappings["purpose"]?.displayName ?: "用途") }, isError = "purpose" in form.lowConfidence, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.amount, { update(form.copy(amount = it, lowConfidence = form.lowConfidence - "amount")) }, label = { Text(mappings["amount"]?.displayName ?: "金额") }, isError = "amount" in form.lowConfidence, modifier = Modifier.fillMaxWidth())
        mappings["paymentPlatform"]?.takeIf { it.enabled }?.let { mapping -> ChoiceField(mapping.displayName, form.paymentPlatform, mapping.options, true) { update(form.copy(paymentPlatform = it)) } }
        mappings["tags"]?.takeIf { it.enabled }?.let { mapping -> TagsField(mapping.displayName, form.tags, mapping.options) { update(form.copy(tags = it)) } }
        mappings["note"]?.takeIf { it.enabled }?.let { OutlinedTextField(form.note, { value -> update(form.copy(note = value)) }, label = { Text(it.displayName) }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
        mappings["project"]?.takeIf { it.enabled }?.let { mapping -> ChoiceField(mapping.displayName, form.project, mapping.options, false) { update(form.copy(project = it)) } }
        Button(onClick = save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存到 ${state.me?.book?.name.orEmpty()}") }
        TextButton(onClick = fresh, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("手工新建空白账目") }
    } }
}

@Composable private fun DateFieldV2(value: String, error: Boolean, change: (String) -> Unit) {
    val context = LocalContext.current
    val parsed = runCatching { OffsetDateTime.parse(value) }.getOrElse { OffsetDateTime.now(ZoneOffset.ofHours(8)) }
    OutlinedTextField(value, {}, readOnly = true, label = { Text("记账日期") }, isError = error, modifier = Modifier.fillMaxWidth(), trailingIcon = {
        TextButton(onClick = { DatePickerDialog(context, { _, y, m, d -> TimePickerDialog(context, { _, h, min -> change(OffsetDateTime.of(y, m + 1, d, h, min, 0, 0, ZoneOffset.ofHours(8)).toString()) }, parsed.hour, parsed.minute, true).show() }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).show() }) { Text("选择") }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChoiceField(label: String, value: String, options: List<String>, required: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) { if (!required) DropdownMenuItem({ Text("不选择") }, { change(""); expanded = false }); options.forEach { item -> DropdownMenuItem({ Text(item) }, { change(item); expanded = false }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TagsField(label: String, selected: Set<String>, options: List<String>, change: (Set<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(selected.joinToString("、"), {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem({ Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(item in selected, null); Text(item) } }, { change(if (item in selected) selected - item else selected + item) }) } }
    }
}

@Composable private fun SettingsV2Screen(state: MultiplayerState, back: () -> Unit, refresh: () -> Unit, connect: () -> Unit, create: () -> Unit, logout: () -> Unit, delete: () -> Unit) {
    val uri = LocalUriHandler.current
    Page("账户与账本", state, back) {
        Text("当前飞书账号", style = MaterialTheme.typography.labelMedium); Text(state.me?.user?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(); Text("当前账本", style = MaterialTheme.typography.labelMedium); Text(state.me?.book?.name ?: "未设置", style = MaterialTheme.typography.titleMedium)
        state.me?.book?.let { Text(it.tableName); Text("今日剩余 AI 识别：${state.me.quota.remaining}/${state.me.quota.limit}"); it.sourceUrl?.let { link -> OutlinedButton(onClick = { uri.openUri(link) }) { Text("在飞书中打开") } } }
        OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) { Text("刷新字段和选项") }
        OutlinedButton(onClick = connect, modifier = Modifier.fillMaxWidth()) { Text("更换为已有表格") }
        OutlinedButton(onClick = create, modifier = Modifier.fillMaxWidth()) { Text("创建并切换到新账本") }
        HorizontalDivider(); TextButton(onClick = logout) { Text("退出登录") }; TextButton(onClick = delete) { Text("注销账户", color = MaterialTheme.colorScheme.error) }
        Text("注销不会删除你飞书中的表格或账目。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun semanticLabel(key: String) = when (key) { "date" -> "记账日期"; "purpose" -> "用途"; "amount" -> "金额"; "paymentPlatform" -> "支付平台"; "tags" -> "标签"; "note" -> "备注"; "project" -> "归属项目"; else -> key }
