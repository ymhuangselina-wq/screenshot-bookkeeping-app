package com.example.screenshotbookkeeping

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
class ReorderGroupState internal constructor(
    private val scrollState: ScrollState,
    private val edgePx: Float,
    private val stepPx: Float,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    var keys: List<String> = emptyList()
    var draggedKey by mutableStateOf<String?>(null); private set
    private var pointerY by mutableFloatStateOf(0f)
    private var grabOffsetY by mutableFloatStateOf(0f)
    private val tops = mutableStateMapOf<String, Float>()
    private val heights = mutableStateMapOf<String, Float>()
    private var viewportTop = 0f
    private var viewportBottom = 0f
    private var moved = false
    private var autoScrollJob: Job? = null
    private var autoScrollSpeed = 0f
    private var originalKeys: List<String> = emptyList()

    fun setViewport(top: Float, bottom: Float) { viewportTop = top; viewportBottom = bottom }
    fun setBounds(key: String, top: Float, height: Float) { tops[key] = top; heights[key] = height }
    fun keyAt(rootY: Float): String? = keys.firstOrNull { key ->
        val top = tops[key] ?: return@firstOrNull false
        rootY in top..(top + (heights[key] ?: 0f))
    }
    fun localY(key: String, rootY: Float): Float = rootY - (tops[key] ?: rootY)
    fun toRootY(localY: Float): Float = viewportTop + localY
    fun start(key: String, localY: Float) {
        originalKeys = keys.toList()
        draggedKey = key; grabOffsetY = localY; pointerY = (tops[key] ?: 0f) + localY; moved = false
        updateAutoScroll()
    }
    fun drag(deltaY: Float, onMove: (Int) -> Unit) {
        val key = draggedKey ?: return
        pointerY += deltaY
        val index = keys.indexOf(key)
        val top = pointerY - grabOffsetY
        val center = top + (heights[key] ?: 0f) / 2f
        val direction = when {
            index > 0 && center < upperSwapLine(keys[index - 1]) -> -1
            index >= 0 && index < keys.lastIndex && center > lowerSwapLine(keys[index + 1]) -> 1
            else -> 0
        }
        if (direction != 0) { moved = true; onMove(direction) }
        updateAutoScroll()
    }
    fun translationFor(key: String): Float = if (draggedKey == key) pointerY - grabOffsetY - (tops[key] ?: 0f) else 0f
    fun finish(onFinished: () -> Unit) {
        val shouldSave = moved
        autoScrollJob?.cancel(); autoScrollJob = null; autoScrollSpeed = 0f
        draggedKey = null; pointerY = 0f; grabOffsetY = 0f; moved = false
        if (shouldSave) onFinished()
    }
    fun cancel(onCancelled: (List<String>) -> Unit) {
        val shouldRestore = moved
        val snapshot = originalKeys
        autoScrollJob?.cancel(); autoScrollJob = null; autoScrollSpeed = 0f
        draggedKey = null; pointerY = 0f; grabOffsetY = 0f; moved = false
        if (shouldRestore) onCancelled(snapshot)
    }
    private fun upperSwapLine(key: String) = (tops[key] ?: Float.MAX_VALUE) + (heights[key] ?: 0f) * .72f
    private fun lowerSwapLine(key: String) = (tops[key] ?: Float.MAX_VALUE) + (heights[key] ?: 0f) * .28f
    private fun updateAutoScroll() {
        val speed = when {
            pointerY < viewportTop + edgePx -> -stepPx
            pointerY > viewportBottom - edgePx -> stepPx
            else -> 0f
        }
        autoScrollSpeed = speed
        if (speed == 0f) { autoScrollJob?.cancel(); autoScrollJob = null; return }
        if (autoScrollJob?.isActive == true) return
        autoScrollJob = scope.launch { while (draggedKey != null) { scrollState.scrollBy(autoScrollSpeed); delay(16) } }
    }
}

@Composable
fun rememberReorderGroupState(scrollState: ScrollState, keys: List<String>): ReorderGroupState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val state = remember(scrollState) {
        ReorderGroupState(scrollState, with(density) { 96f * density.density }, with(density) { 16f * density.density }, scope)
    }
    state.keys = keys
    return state
}

fun Modifier.reorderViewport(state: ReorderGroupState) = onGloballyPositioned {
    val bounds = it.boundsInRoot(); state.setViewport(bounds.top, bounds.bottom)
}

@Composable
fun Modifier.reorderItem(
    key: String,
    state: ReorderGroupState
): Modifier {
    val selected = state.draggedKey == key
    val itemAlpha by animateFloatAsState(if (state.draggedKey != null && !selected) .60f else 1f, label = "reorderAlpha")
    return this
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot(); state.setBounds(key, bounds.top, bounds.height)
        }
        .zIndex(if (selected) 10f else 0f)
        .graphicsLayer { translationY = state.translationFor(key); shadowElevation = if (selected) 18f else 0f }
        .alpha(itemAlpha)
}

@Composable
fun Modifier.reorderHandle(
    key: String,
    state: ReorderGroupState,
    onMove: (Int) -> Unit,
    onFinished: () -> Unit,
    onCancelled: (List<String>) -> Unit
): Modifier {
    val haptics = LocalHapticFeedback.current
    var handleTopInRoot by remember { mutableFloatStateOf(0f) }
    return this
        .onGloballyPositioned { handleTopInRoot = it.positionInRoot().y }
        .pointerInput(key) {
            detectDragGestures(
                onDragStart = { offset ->
                    val rootY = handleTopInRoot + offset.y
                    state.start(key, state.localY(key, rootY))
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    state.drag(dragAmount.y, onMove)
                },
                onDragEnd = {
                    state.finish {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onFinished()
                    }
                },
                onDragCancel = { state.cancel(onCancelled) }
            )
        }
}
