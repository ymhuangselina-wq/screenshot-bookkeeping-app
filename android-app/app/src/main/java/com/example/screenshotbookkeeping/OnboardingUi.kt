package com.example.screenshotbookkeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ServiceOnboardingScreen(
    state: UiState,
    connect: (String, String, String) -> Unit,
    demo: () -> Unit
) {
    var url by remember(state.workerUrl) { mutableStateOf(state.workerUrl) }
    var key by remember { mutableStateOf("") }
    val canConnect = url.trim().startsWith("https://") && key.isNotBlank() && !state.busy

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            OnboardingProgress(activeStep = 1)
            Spacer(Modifier.height(34.dp))
            Surface(
                modifier = Modifier.size(58.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(29.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("先连接记账服务", fontSize = 30.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
            Text(
                "服务负责识别截图，并把你确认后的账目写入飞书。连接成功后，下一步会让你选择记账表格。",
                modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务 HTTPS 地址") },
                placeholder = { Text("https://…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp)
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("个人访问密钥") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(19.dp)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp)
            )
            state.error?.let {
                Text(it, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Button(
                onClick = { connect(url.trim(), key, "") },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(54.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("正在验证服务…", fontWeight = FontWeight.Bold)
                } else Text("连接并继续", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = demo, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("先体验演示")
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    "地址和密钥仅加密保存在本机。公开安装包不会预置任何人的服务地址或密钥。",
                    Modifier.padding(15.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun OnboardingProgress(activeStep: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf("连接服务", "选择表格").forEachIndexed { index, label ->
            val step = index + 1
            val active = step <= activeStep
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("$step", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(7.dp))
                Text(label, fontSize = 12.sp, fontWeight = if (step == activeStep) FontWeight.Bold else FontWeight.Medium, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (index == 0) {
                Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}
