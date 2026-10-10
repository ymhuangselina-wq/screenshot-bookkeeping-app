package com.example.screenshotbookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import java.text.DateFormat
import java.util.Date

@Composable
fun PersonalBookFlow(state: UiState, vm: MainViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.fieldSettingsUpdated, state.bookToastMessage) {
        val message = state.bookToastMessage ?: if (state.fieldSettingsUpdated) "字段设置已更新" else null
        if (message != null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            if (state.bookToastMessage != null) vm.consumeBookToast()
            if (state.fieldSettingsUpdated) vm.consumeFieldSettingsUpdated()
        }
    }
    BackHandler(enabled = !state.busy, onBack = if (state.bookScreen == BookScreen.MANAGE_FIELDS) vm::closeManagedFields else vm::closeBooks)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = state.bookScreen,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (slideInHorizontally(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                            initialOffsetX = { it / 4 }
                        ) + fadeIn(animationSpec = tween(240))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(320, easing = FastOutSlowInEasing),
                                targetOffsetX = { -it / 4 }
                            ) + fadeOut(animationSpec = tween(240)))
                    },
                    label = "book-flow"
                ) { screen ->
                    when (screen) {
                        BookScreen.LIST -> SwitchBookPage(state, vm)
                        BookScreen.MANAGE -> BookManagementPage(state, vm)
                        BookScreen.CONNECT -> ConnectBookPage(state, vm)
                        BookScreen.MAP_FIELDS -> BookMappingPage(state, vm, managing = false)
                        BookScreen.MANAGE_FIELDS -> BookMappingPage(state, vm, managing = true)
                        BookScreen.NONE -> Unit
                    }
                }
                if (state.busy && state.processingBookId == null && state.bookScreen != BookScreen.MANAGE_FIELDS) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp)
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
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(data.visuals.message, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BookPage(
    title: String,
    back: () -> Unit,
    providedScrollState: androidx.compose.foundation.ScrollState? = null,
    reorderState: ReorderGroupState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = providedScrollState ?: rememberScrollState()
    Scaffold(topBar = { TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "返回") } }, actions = actions) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .then(if (reorderState != null) Modifier.reorderViewport(reorderState) else Modifier)
                .verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content
        )
    }
}

@Composable private fun SwitchBookPage(state: UiState, vm: MainViewModel) {
    BookPage("切换账本", vm::closeBooks) {
        Text("选择一个账本，切换后将返回主页面。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.books.books.isEmpty()) Text("还没有可切换的账本，请先连接一个多维表格。")
        state.books.books.forEach { book ->
            val current = book.id == state.books.currentBookId
            BookSummaryCard(
                book = book,
                current = current,
                processing = state.processingBookId == book.id,
                onClick = { if (!current) vm.requestSwitchBook(book.id) }
            )
        }
        Spacer(Modifier.height(2.dp))
        Button(
            onClick = vm::openBookManagement,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("管理账本", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun BookManagementPage(state: UiState, vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var deleteMode by rememberSaveable { mutableStateOf(false) }
    BookPage(
        title = "账本管理",
        back = vm::closeBooks,
        actions = {
            IconButton(onClick = vm::startConnectBook, enabled = !state.busy) { Icon(Icons.Default.Add, "连接新账本", Modifier.size(22.dp)) }
            IconButton(onClick = { deleteMode = !deleteMode }, enabled = !state.busy) {
                Icon(Icons.Default.DeleteOutline, if (deleteMode) "退出删除账本" else "删除账本", Modifier.size(21.dp), tint = if (deleteMode) MaterialTheme.colorScheme.error else LocalContentColor.current)
            }
            IconButton(onClick = vm::refreshBookCatalog, enabled = !state.busy) { Icon(Icons.Default.Refresh, "刷新全部账本", Modifier.size(21.dp)) }
        }
    ) {
        Text(
            if (deleteMode) "选择要移除的账本。当前账本需要先切换后才能移除。" else "点击卡片查看字段；可直接切换账本或在飞书中查看记录。",
            fontSize = 13.sp, lineHeight = 20.sp,
            color = if (deleteMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        state.books.books.forEach { book ->
            val current = book.id == state.books.currentBookId
            val cardInteraction = remember(book.id) { MutableInteractionSource() }
            val cardPressed by cardInteraction.collectIsPressedAsState()
            val baseCardColor = if (current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
            val cardColor by animateColorAsState(
                targetValue = if (cardPressed) Color(0xFFFFF3EA) else baseCardColor,
                label = "book-card-press"
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !deleteMode && !state.busy,
                        interactionSource = cardInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = { vm.openBookFields(book.id) }
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                border = BorderStroke(1.dp, if (current) Color(0xFFEAD9CF) else MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = if (current) 0.dp else 1.dp)
            ) {
                Column(
                    Modifier.padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = if (deleteMode) 10.dp else 6.dp
                    )
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(book.bookName, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                book.tableName,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when {
                            current -> CurrentBadge()
                            else -> Button(
                                onClick = { vm.requestSwitchBook(book.id) },
                                enabled = !state.busy,
                                modifier = Modifier.height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF2EEF0),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    disabledContainerColor = Color(0xFFF2EEF0),
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                            ) {
                                Text("切换", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text(
                        "最近使用：${formatBookDate(book.lastUsedAt)}",
                        modifier = Modifier.padding(top = 12.dp),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    if (deleteMode) {
                        TextButton(
                            onClick = { vm.removeBook(book.id) },
                            enabled = !current && !state.busy,
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (current) "当前账本不可移除" else "移除账本") }
                    } else {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = { FeishuLinks.open(context, book.sourceUrl) },
                                modifier = Modifier.offset(x = (-8).dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.OpenInNew, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("打开飞书", fontWeight = FontWeight.SemiBold)
                            }
                            Row(
                                modifier = Modifier.padding(end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "查看字段",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BookSummaryCard(book: PersonalBookDto, current: Boolean, processing: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = !processing,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (current) Color(0xFFEAD9CF) else MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (current) 0.dp else 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(book.bookName, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(book.tableName, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Color(0xFF615960))
                }
                when { processing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); current -> CurrentBadge(); else -> Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFFF2EDEF)) { Text("切换", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF655E64)) } }
            }
            Text("最近使用：${formatBookDate(book.lastUsedAt)}", modifier = Modifier.padding(top = 12.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun CurrentBadge() = Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFFFFE4D0)) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = Color(0xFF97450E))
        Spacer(Modifier.width(4.dp)); Text("当前", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF97450E))
    }
}

private fun formatBookDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@Composable private fun ConnectBookPage(state: UiState, vm: MainViewModel) = BookPage("新增账本", vm::openBookManagement) {
    Text(
        "粘贴飞书多维表格链接，读取后选择需要连接的数据表。",
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = state.bookUrl,
        onValueChange = vm::setBookUrl,
        label = { Text("飞书多维表格链接", fontSize = 13.sp) },
        placeholder = { Text("https://…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f)) },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = RoundedCornerShape(16.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp)
    )
    Button(
        onClick = { vm.inspectBook() },
        enabled = state.bookUrl.startsWith("http") && !state.busy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("读取表格", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, lineHeight = 20.sp) }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
        OutlinedButton(
            onClick = { vm.inspectBook() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("重试", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
    state.inspection?.takeIf { it.selectionRequired }?.let { result ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("选择数据表", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        result.tables.forEach { table ->
            OutlinedButton(
                onClick = { vm.inspectBook(table.table_id) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(table.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun BookMappingPage(state: UiState, vm: MainViewModel, managing: Boolean) {
    val scrollState = rememberScrollState()
    val reorderState = rememberReorderGroupState(scrollState, state.fieldLayoutDrafts.map { it.fieldId })
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    var reorderStartIds by remember { mutableStateOf(emptyList<String>()) }
    val currentBook = state.books.books.find { it.id == (state.managingBookId ?: state.books.currentBookId) }
    val context = androidx.compose.ui.platform.LocalContext.current
    BookPage(
        title = if (managing) "字段设置" else "确认字段映射",
        back = if (managing) vm::closeManagedFields else vm::startConnectBook,
        providedScrollState = scrollState,
        reorderState = if (managing) reorderState else null,
        actions = {
            if (managing) currentBook?.sourceUrl?.let { link ->
                TextButton(
                    onClick = { FeishuLinks.open(context, link) },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = "在飞书中打开",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("在飞书中查看", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) {
    if (managing && state.fieldLayoutDrafts.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, Color(0xFFEADFD9))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("字段所属账本", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        currentBook?.bookName ?: "当前账本",
                        modifier = Modifier.padding(top = 5.dp),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("数据表：${currentBook?.tableName.orEmpty()}", modifier = Modifier.padding(top = 3.dp), fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF625B62))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (reorderMode) "调整字段顺序" else "账本字段",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            TextButton(
                onClick = {
                    if (!reorderMode) {
                        reorderStartIds = state.fieldLayoutDrafts.map { it.fieldId }
                        reorderMode = true
                    } else {
                        reorderMode = false
                        val currentIds = state.fieldLayoutDrafts.map { it.fieldId }
                        if (currentIds != reorderStartIds) vm.persistManagedFieldLayout()
                        reorderStartIds = emptyList()
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                if (!reorderMode) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(if (reorderMode) "完成" else "排序", fontWeight = FontWeight.SemiBold)
            }
        }
        if (reorderMode) Text("按住右侧手柄拖动，调整字段在主页面的显示顺序。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.fieldLayoutDrafts.forEach { field ->
                key(field.fieldId) {
                    val selected = reorderState.draggedKey == field.fieldId
                    Surface(
                        modifier = Modifier.fillMaxWidth().reorderItem(field.fieldId, reorderState),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(field.displayName, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (field.fieldType in 1..5) fieldTypeName(field.fieldType) + if (field.required) " · 必填" else "" else "App 暂不支持录入",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (field.fieldType in 1..5) {
                                Switch(
                                    checked = field.required,
                                    onCheckedChange = { vm.setManagedFieldRequired(field.fieldId, it) },
                                    modifier = Modifier.size(width = 46.dp, height = 32.dp),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = Color(0xFFD8D1D6),
                                        uncheckedBorderColor = Color.Transparent
                                    )
                                )
                            } else Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFFF2EDEF)) {
                                Text("不可用", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (reorderMode) Box(
                                Modifier.size(width = 40.dp, height = 48.dp).reorderHandle(field.fieldId, reorderState, { vm.moveManagedField(field.fieldId, it) }, {}, vm::restoreManagedFieldOrder),
                                contentAlignment = Alignment.Center
                            ) {
                                SixDotDragHandle("拖动${field.displayName}排序")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Button(onClick = vm::startBookkeepingFromManagedBook, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("开始记账", fontWeight = FontWeight.SemiBold) }
        Text("字段显示设置仅影响截图记账，不会修改飞书表格。", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        return@BookPage
    }
    val directFields = if (managing) state.config?.fields.orEmpty() else state.inspection?.fields.orEmpty()
    if (directFields.isNotEmpty()) {
        Text(if (managing) "当前记账页会按以下飞书字段直接展示。字段名称和格式以飞书为准。" else "已读取到 ${directFields.size} 个字段。连接后将按原名称和格式直接展示，不做默认字段映射，也不会新增表头。")
        directFields.forEach { field ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(field.nameOrDisplay(), style = MaterialTheme.typography.titleSmall); Text(fieldTypeName(field.typeOrFieldType()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (field.primaryOrFalse()) AssistChip(onClick = {}, label = { Text("主字段") })
            } }
        }
        if (!managing) Button(onClick = vm::connectInspectedBook, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("按当前表结构连接") }
        else Text("如需改名、增删字段或修改格式，请在飞书多维表格中操作，然后返回 App 点击“刷新当前账本”。", style = MaterialTheme.typography.bodySmall)
        return@BookPage
    }
    if (!managing && state.inspection?.fields?.isEmpty() == true) {
        Text("没有读取到可展示的字段，请先在飞书中创建字段后重试。", color = MaterialTheme.colorScheme.error)
        return@BookPage
    }
    if (!managing && state.inspection?.empty == true) Text("未识别到记账字段。请选择需要的字段，我们会自动配置这张数据表。", color = MaterialTheme.colorScheme.primary)
    else Text(if (managing) "可修改字段名称、启用可选字段及编辑选项。不会删除原字段或已有账目。" else "请确认每个记账字段对应的飞书表头。缺失字段会在确认后创建。")
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.mappingDrafts.forEachIndexed { index, mapping ->
        FieldMappingCard(state, mapping, managing) { change -> vm.updateBookMapping(index, change) }
    }
    Button(onClick = if (managing) vm::requestFieldsConfirmation else vm::connectInspectedBook, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (managing) "预览并同步到飞书" else "确认配置并切换") }
}
}

private fun Any.nameOrDisplay(): String = when (this) { is FeishuFieldDto -> name; is DynamicFieldDto -> displayName; else -> "字段" }
private fun Any.typeOrFieldType(): Int = when (this) { is FeishuFieldDto -> type; is DynamicFieldDto -> fieldType; else -> 0 }
private fun Any.primaryOrFalse(): Boolean = when (this) { is FeishuFieldDto -> primary; is DynamicFieldDto -> primary; else -> false }
private fun fieldTypeName(type: Int) = when (type) { 1 -> "文本"; 2 -> "数字"; 3 -> "单选"; 4 -> "多选"; 5 -> "日期"; else -> "暂不支持的字段格式" }

@Composable private fun FieldMappingCard(state: UiState, mapping: MappingDraft, managing: Boolean, update: ((MappingDraft) -> MappingDraft) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(semanticLabel(mapping.semanticKey), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            if (mapping.required) Text("必选", color = MaterialTheme.colorScheme.primary)
            else Switch(mapping.enabled, { checked -> update { it.copy(enabled = checked) } })
        }
        if (mapping.enabled) {
            if (!managing) ExistingPersonalFieldPicker(state.inspection?.fields.orEmpty().filter { it.type == mapping.expectedType }, mapping) { field ->
                update { it.copy(fieldId = field?.id, displayName = field?.name ?: semanticLabel(it.semanticKey), optionsText = field?.options?.joinToString("、") ?: it.optionsText, status = if (field == null) "missing" else "matched") }
            }
            OutlinedTextField(mapping.displayName, { value -> update { it.copy(displayName = value) } }, label = { Text("表头名称") }, enabled = managing || mapping.fieldId == null, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            if (mapping.expectedType == 3 || mapping.expectedType == 4) OutlinedTextField(mapping.optionsText, { value -> update { it.copy(optionsText = value) } }, label = { Text("选项，用顿号分隔") }, enabled = managing || mapping.fieldId == null, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            val status = when { managing && mapping.fieldId == null -> "将创建"; mapping.fieldId != null -> "已匹配：${mapping.displayName}"; mapping.status == "conflict" -> "类型冲突，将创建新字段"; else -> "缺失，将自动创建" }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("已关闭，记账页将不显示该字段。", style = MaterialTheme.typography.bodySmall)
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExistingPersonalFieldPicker(fields: List<FeishuFieldDto>, mapping: MappingDraft, choose: (FeishuFieldDto?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        val selected = mapping.fieldId?.let { id -> fields.find { it.id == id }?.name } ?: "创建新字段"
        OutlinedTextField(selected, {}, readOnly = true, label = { Text("对应的飞书字段") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        ExposedDropdownMenu(expanded, { expanded = false }) {
            fields.forEach { field -> DropdownMenuItem({ Text(field.name) }, { choose(field); expanded = false }) }
            DropdownMenuItem({ Text("创建新字段") }, { choose(null); expanded = false })
        }
    }
}

private fun semanticLabel(key: String) = when (key) { "date" -> "记账日期"; "purpose" -> "用途"; "amount" -> "金额"; "paymentPlatform" -> "支付平台"; "tags" -> "标签"; "note" -> "备注"; "project" -> "归属项目"; else -> key }
