@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.RelationType
import com.example.familytree.data.filteredBy
import com.example.familytree.graph.TreeLayout
import com.example.familytree.graph.TreeLayoutEngine
import com.example.familytree.ui.theme.LocalI18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

enum class TreeMode { TREE, LINEAGE, BRANCH }

/**
 * 家谱拓扑图：
 *  - 三种视图：树形图（自上而下）/ 世系图（自左向右）/ 分支图（以某成员为中心的祖先+后代分支）；
 *  - 点击节点查看信息、长按编辑、连线模式建关系、搜索定位与关系筛选高亮；
 *  - 大数据量：布局在后台线程计算，绘制时按可视区域裁剪（视口复用），保证流畅。
 */
@Composable
fun TreeScreen(
    viewModel: FamilyViewModel,
    onEditPerson: (String) -> Unit,
    onConnect: (String, String) -> Unit,
) {
    val i18n = LocalI18n.current
    val context = LocalContext.current
    val data = viewModel.data
    val treeData = remember(data, viewModel.treeFamilyFilter) { data.filteredBy(viewModel.treeFamilyFilter) }
    val textMeasurer = rememberTextMeasurer()
    val bg = TreeBackgrounds[viewModel.bgStyle.mod(TreeBackgrounds.size)]

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showBgPicker by remember { mutableStateOf(false) }
    var connectMode by remember { mutableStateOf(false) }
    var showNewFamily by remember { mutableStateOf(false) }

    var mode by rememberSaveable { mutableStateOf(TreeMode.TREE) }
    var branchFocusId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFocusPicker by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var relFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showCustomEdges by rememberSaveable { mutableStateOf(false) } // 推断虚线默认关闭

    val byIdAll = remember(treeData) { treeData.persons.associateBy { it.id } }

    // 分支图：以所选成员为中心裁剪数据
    val effectiveData = if (mode == TreeMode.BRANCH && branchFocusId != null) {
        remember(treeData, branchFocusId) { TreeLayoutEngine.subtree(treeData, branchFocusId!!) }
    } else treeData

    // 异步布局（后台线程计算，大数据量不阻塞 UI）。
    // layout 不随数据变化重置：更新期间继续显示旧图，避免白屏闪烁与视野跳动。
    var layout by remember { mutableStateOf<TreeLayout?>(null) }
    // 布局上下文（视图模式/所选家族/分支中心）变化时才整体重新适配；
    // 仅数据变化时保持视野锚定，不让拓扑图乱飘。
    val layoutContextKey = listOf(mode, viewModel.treeFamilyFilter, branchFocusId)
    var lastLayoutContextKey by remember { mutableStateOf<List<Any?>>(emptyList()) }
    var pendingFit by remember { mutableStateOf(true) }

    val byId = remember(effectiveData) { effectiveData.persons.associateBy { it.id } }

    // 搜索 / 关系筛选高亮集合
    val highlightIds = remember(effectiveData, query, relFilter) {
        val set = mutableSetOf<String>()
        val q = query.trim()
        if (q.isNotEmpty()) {
            effectiveData.persons.filter { it.name.contains(q, ignoreCase = true) }.forEach { set.add(it.id) }
        } else if (relFilter != null) {
            effectiveData.relations.filter { r ->
                when (relFilter) {
                    "parent" -> r.type == RelationType.FATHER || r.type == RelationType.MOTHER ||
                        r.type == RelationType.SON || r.type == RelationType.DAUGHTER || r.type == RelationType.PARENT
                    "spouse" -> r.type == RelationType.SPOUSE
                    "sibling" -> r.type == RelationType.SIBLING
                    else -> false
                }
            }.forEach { set.add(it.fromId); set.add(it.toId) }
        }
        set
    }
    val searchResults = remember(effectiveData, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else effectiveData.persons.filter { it.name.contains(q, ignoreCase = true) }
    }

    fun fit() {
        val lay = layout ?: return
        val w = screenSize.width.toFloat()
        val h = screenSize.height.toFloat()
        if (w <= 0f || h <= 0f || lay.width <= 0f || lay.height <= 0f) return
        val s = min((w - 40f) / lay.width, (h - 40f) / lay.height).coerceIn(0.18f, 2f)
        scale = s
        offset = Offset((w - lay.width * s) / 2f, (h - lay.height * s) / 2f)
    }

    fun zoomBy(factor: Float) {
        val s = (scale * factor).coerceIn(0.18f, 4f)
        val k = s / scale
        val c = Offset(screenSize.width / 2f, screenSize.height / 2f)
        scale = s
        offset = c - (c - offset) * k
    }

    fun centerOn(id: String) {
        val lay = layout ?: return
        val r = lay.boxes[id] ?: return
        val c = Offset(screenSize.width / 2f, screenSize.height / 2f)
        val targetScale = scale.coerceAtLeast(0.6f)
        scale = targetScale
        offset = c - Offset(r.center.x * targetScale, r.center.y * targetScale)
        selectedId = null
    }

    // 布局计算与视野管理：
    //  - 数据更新：锚定屏幕中心成员在新布局中的位置，视野“定得住”不乱飘；
    //  - 切换模式/家族/分支中心或首次进入：重新适配全图。
    LaunchedEffect(effectiveData, mode) {
        // 记录数据更新前屏幕中心对应的人，作为重新布局后的定位锚点
        val oldLayout = layout
        var anchorId: String? = null
        var anchorCenter: Offset? = null
        if (oldLayout != null && screenSize.width > 0 && screenSize.height > 0) {
            val cx = (screenSize.width / 2f - offset.x) / scale
            val cy = (screenSize.height / 2f - offset.y) / scale
            anchorId = oldLayout.boxes.entries.firstOrNull { it.value.contains(Offset(cx, cy)) }?.key
            anchorCenter = anchorId?.let { oldLayout.boxes[it]?.center }
        }
        // 自动推断会产生连续多次数据更新：短暂合并为一次布局，避免反复重排跳动
        delay(140)
        val newLayout = withContext(Dispatchers.Default) {
            when (mode) {
                TreeMode.LINEAGE -> TreeLayoutEngine.layoutLineage(effectiveData)
                else -> TreeLayoutEngine.layout(effectiveData)
            }
        }
        layout = newLayout
        if (lastLayoutContextKey != layoutContextKey) {
            lastLayoutContextKey = layoutContextKey
            if (screenSize.width > 0 && screenSize.height > 0) fit() else pendingFit = true
        } else if (anchorId != null && anchorCenter != null) {
            val newCenter = newLayout.boxes[anchorId]?.center
            if (newCenter != null) {
                offset += Offset(anchorCenter.x - newCenter.x, anchorCenter.y - newCenter.y) * scale
            }
        }
    }

    // 首次进入或切换上下文后，等窗口尺寸就绪时自动适配一次
    LaunchedEffect(layout?.width, layout?.height, screenSize) {
        if (pendingFit && screenSize.width > 0 && screenSize.height > 0 && layout != null) {
            fit()
            pendingFit = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar(
            title = {
                // 统计信息移到标题下方，保证“家谱拓扑图”始终单行显示不被挤压换行
                Column {
                    Text(
                        i18n.s("tree_title"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        i18n.s(
                            "tree_stats",
                            effectiveData.persons.size,
                            effectiveData.relations.size,
                            layout?.generations ?: 0,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = {
                    connectMode = !connectMode
                    selectedId = null
                }) {
                    Icon(
                        Icons.Default.Link,
                        i18n.s("connect_mode"),
                        tint = if (connectMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showBgPicker = true }) {
                    Icon(Icons.Default.Palette, i18n.s("bg_style"))
                }
            },
        )

        FamilyChipsRow(
            families = data.families,
            selection = viewModel.treeFamilyFilter,
            onSelect = { viewModel.treeFamilyFilter = it },
            onNew = { showNewFamily = true },
            trailing = {
                // 刷新：按当前所选家族，依据已推断的关系自动补全该家族拓扑
                AssistChip(
                    onClick = {
                        val n = viewModel.refreshFamilyRelations(viewModel.treeFamilyFilter)
                        val msg = when {
                            n > 0 -> i18n.s("refreshed_toast", n)
                            n < 0 -> i18n.s("refreshed_cleaned", -n)
                            else -> i18n.s("refreshed_none")
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    label = { Text("🔄 " + i18n.s("refresh_relations")) },
                )
            },
        )

        // 视图模式切换
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = mode == TreeMode.TREE, onClick = { mode = TreeMode.TREE }, label = { Text(i18n.s("mode_tree")) })
            FilterChip(selected = mode == TreeMode.LINEAGE, onClick = { mode = TreeMode.LINEAGE }, label = { Text(i18n.s("mode_lineage")) })
            FilterChip(
                selected = mode == TreeMode.BRANCH,
                onClick = {
                    mode = TreeMode.BRANCH
                    if (branchFocusId == null) showFocusPicker = true
                },
                label = { Text(i18n.s("mode_branch")) },
            )
            if (mode == TreeMode.BRANCH) {
                AssistChip(
                    onClick = { showFocusPicker = true },
                    label = { Text(branchFocusId?.let { byIdAll[it]?.name } ?: i18n.s("branch_focus")) },
                )
            }
        }

        // 搜索 + 关系筛选
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(i18n.s("tree_search_hint")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, i18n.s("clear")) }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = relFilter == null, onClick = { relFilter = null }, label = { Text(i18n.s("family_all")) })
            FilterChip(selected = relFilter == "parent", onClick = { relFilter = "parent" }, label = { Text(i18n.s("rel_parent")) })
            FilterChip(selected = relFilter == "spouse", onClick = { relFilter = "spouse" }, label = { Text(i18n.s("rel_spouse")) })
            FilterChip(selected = relFilter == "sibling", onClick = { relFilter = "sibling" }, label = { Text(i18n.s("rel_sibling")) })
            // 推断虚线（自定义/跨代推断关系）显示开关
            FilterChip(
                selected = showCustomEdges,
                onClick = { showCustomEdges = !showCustomEdges },
                label = { Text(i18n.s("toggle_custom_edges")) },
            )
            if (query.isNotBlank()) {
                searchResults.take(8).forEach { p ->
                    AssistChip(onClick = { centerOn(p.id) }, label = { Text(p.name) })
                }
                if (searchResults.isEmpty()) {
                    Text(
                        i18n.s("no_match"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { screenSize = it }
                .background(bg.bottom),
        ) {
            when {
                layout == null -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            i18n.s("tree_loading"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                effectiveData.persons.isEmpty() -> {
                    Text(
                        i18n.s("tree_empty"),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> {
                    val lay = layout!!
                    val edgeColor = bg.edge
                    val selectColor = MaterialTheme.colorScheme.primary
                    val selectStroke = with(LocalDensity.current) { 3.dp.toPx() }
                    val genStyle = TextStyle(
                        color = bg.edge.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    // 变换层放在外层 Box：平移/缩放只更新 GPU 变换，内层 Canvas 内容不变时不重绘
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            // 可视区域（布局坐标），用于视口裁剪：屏外内容不绘制
                            val visL = -offset.x / scale - 300f
                            val visT = -offset.y / scale - 300f
                            val visR = (screenSize.width - offset.x) / scale + 300f
                            val visB = (screenSize.height - offset.y) / scale + 300f

                            fun visible(r: Rect): Boolean =
                                r.right >= visL && r.left <= visR && r.bottom >= visT && r.top <= visB

                            // 渐变图纸背景
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(bg.top, bg.bottom),
                                    startY = -2000f,
                                    endY = lay.height + 2000f,
                                ),
                                topLeft = Offset(-2000f, -2000f),
                                size = Size(lay.width + 4000f, lay.height + 4000f),
                            )

                            if (mode != TreeMode.LINEAGE) {
                                // 隔代色带
                                for (g in 1 until lay.generations step 2) {
                                    val y = TreeLayoutEngine.PAD + g * TreeLayoutEngine.ROW_H
                                    if (y > visB || y + TreeLayoutEngine.ROW_H < visT) continue
                                    drawRect(
                                        color = bg.band,
                                        topLeft = Offset(visL.coerceAtLeast(-2000f), y),
                                        size = Size(visR - visL, TreeLayoutEngine.ROW_H),
                                    )
                                }
                            }

                            // 点阵网格（仅可视范围）
                            var gx = ((visL / 44f).toInt() * 44f).coerceAtLeast(0f)
                            while (gx <= min(visR, lay.width)) {
                                var gy = ((visT / 44f).toInt() * 44f).coerceAtLeast(0f)
                                while (gy <= min(visB, lay.height)) {
                                    drawCircle(bg.grid, radius = 1.6f, center = Offset(gx, gy))
                                    gy += 44f
                                }
                                gx += 44f
                            }

                            // 代际标签
                            for (g in 0 until lay.generations) {
                                val label = i18n.s("gen_label", g + 1)
                                val l = textMeasurer.measure(label, genStyle)
                                val x: Float
                                val y: Float
                                if (mode == TreeMode.LINEAGE) {
                                    x = TreeLayoutEngine.PAD + g * TreeLayoutEngine.COL_GAP +
                                        (TreeLayoutEngine.BOX_W - l.size.width) / 2f
                                    y = TreeLayoutEngine.PAD - 20f
                                } else {
                                    x = 2f
                                    y = TreeLayoutEngine.PAD + g * TreeLayoutEngine.ROW_H +
                                        (TreeLayoutEngine.BOX_H - l.size.height) / 2f
                                }
                                if (x > visR || x < visL || y > visB || y < visT) continue
                                drawText(l, topLeft = Offset(x, y))
                            }

                            // 连线（视口裁剪；推断虚线可整体隐藏）
                            lay.paths.forEach { path ->
                                if (path.isCustom && !showCustomEdges) return@forEach
                                var minX = Float.MAX_VALUE
                                var maxX = -Float.MAX_VALUE
                                var minY = Float.MAX_VALUE
                                var maxY = -Float.MAX_VALUE
                                path.points.forEach { pt ->
                                    if (pt.x < minX) minX = pt.x
                                    if (pt.x > maxX) maxX = pt.x
                                    if (pt.y < minY) minY = pt.y
                                    if (pt.y > maxY) maxY = pt.y
                                }
                                if (maxX < visL || minX > visR || maxY < visT || minY > visB) return@forEach
                                val p = Path()
                                path.points.forEachIndexed { i, pt ->
                                    if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                                }
                                drawPath(
                                    path = p,
                                    color = edgeColor,
                                    style = Stroke(
                                        width = 2.5f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                        pathEffect = if (path.dashed) {
                                            PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                                        } else {
                                            null
                                        },
                                    ),
                                )
                            }

                            // 节点（视口裁剪）
                            lay.boxes.forEach { (id, rect) ->
                                if (!visible(rect)) return@forEach
                                val person = byId[id] ?: return@forEach
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = bg.shadowAlpha),
                                    topLeft = rect.topLeft + Offset(0f, 3f),
                                    size = rect.size,
                                    cornerRadius = CornerRadius(9f),
                                )
                                drawRoundRect(
                                    color = personColor(person, effectiveData.persons),
                                    topLeft = rect.topLeft,
                                    size = rect.size,
                                    cornerRadius = CornerRadius(9f),
                                )
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.25f),
                                    topLeft = rect.topLeft,
                                    size = Size(rect.width, 4f),
                                    cornerRadius = CornerRadius(9f),
                                )
                                // 搜索/筛选高亮光环
                                if (id in highlightIds) {
                                    drawCircle(
                                        color = selectColor.copy(alpha = 0.85f),
                                        radius = rect.width * 0.62f,
                                        center = rect.center,
                                        style = Stroke(width = 4f),
                                    )
                                }
                                if (id == selectedId) {
                                    drawRoundRect(
                                        color = selectColor,
                                        topLeft = rect.topLeft,
                                        size = rect.size,
                                        cornerRadius = CornerRadius(9f),
                                        style = Stroke(width = selectStroke),
                                    )
                                }

                                val nameText = person.name.ifBlank { "?" }
                                var nameSp = 17
                                var nameLayout = textMeasurer.measure(
                                    text = nameText,
                                    style = TextStyle(color = Color.White, fontSize = nameSp.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                )
                                while (nameLayout.size.width > rect.width - 10f && nameSp > 9) {
                                    nameSp -= 2
                                    nameLayout = textMeasurer.measure(
                                        text = nameText,
                                        style = TextStyle(color = Color.White, fontSize = nameSp.sp, fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                    )
                                }
                                drawText(
                                    nameLayout,
                                    topLeft = Offset(
                                        rect.left + (rect.width - nameLayout.size.width) / 2f,
                                        rect.top + (rect.height - nameLayout.size.height) / 2f,
                                    ),
                                )
                            }
                        }
                    }

                    // 手势层：平移缩放 + 点选/长按
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    // 以手势中心为缩放锚点，平移/缩放不漂移
                                    val newScale = (scale * zoom).coerceIn(0.18f, 4f)
                                    val k = newScale / scale
                                    offset = centroid - (centroid - offset) * k + pan
                                    scale = newScale
                                }
                            }
                            .pointerInput(lay) {
                                detectTapGestures(
                                    onTap = { tap ->
                                        val local = Offset((tap.x - offset.x) / scale, (tap.y - offset.y) / scale)
                                        val hitId = lay.boxes.entries.firstOrNull { it.value.contains(local) }?.key
                                        if (connectMode) {
                                            when {
                                                hitId == null -> selectedId = null
                                                selectedId == null -> selectedId = hitId
                                                selectedId == hitId -> selectedId = null
                                                else -> {
                                                    onConnect(selectedId!!, hitId)
                                                    selectedId = null
                                                    connectMode = false
                                                }
                                            }
                                        } else {
                                            if (hitId != null) onEditPerson(hitId)
                                        }
                                    },
                                    onLongPress = { tap ->
                                        val local = Offset((tap.x - offset.x) / scale, (tap.y - offset.y) / scale)
                                        lay.boxes.entries.firstOrNull { it.value.contains(local) }?.key
                                            ?.let { onEditPerson(it) }
                                    },
                                )
                            },
                    )

                    // 顶部图例与提示
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LegendDot(PersonPalette[0])
                            Text(i18n.s("male"), style = MaterialTheme.typography.labelSmall)
                            LegendDot(PersonPalette[1])
                            Text(i18n.s("female"), style = MaterialTheme.typography.labelSmall)
                            LegendDot(PersonPalette[2])
                            Text(i18n.s("unknown"), style = MaterialTheme.typography.labelSmall)
                            Text(
                                i18n.s("legend_edge"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (connectMode) {
                            AssistChip(
                                onClick = {
                                    connectMode = false
                                    selectedId = null
                                },
                                label = { Text(i18n.s("connect_hint")) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            Text(
                                i18n.s("tree_hint"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }

                        val sel = selectedId?.let { byId[it] }
                        if (connectMode && sel != null) {
                            AssistChip(
                                onClick = { selectedId = null },
                                label = { Text(i18n.s("selected_node_hint", sel.name)) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    // 缩放控制
                    Column(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ZoomFab(Icons.Default.ZoomIn) { zoomBy(1.25f) }
                        ZoomFab(Icons.Default.ZoomOut) { zoomBy(0.8f) }
                        ZoomFab(Icons.Default.FitScreen) { fit() }
                    }
                }
            }
        }
    }

    // 分支图中心成员选择
    if (showFocusPicker) {
        AlertDialog(
            onDismissRequest = { showFocusPicker = false },
            title = { Text(i18n.s("branch_focus")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PersonPicker(
                        label = i18n.s("member_b"),
                        persons = treeData.persons,
                        selectedId = branchFocusId,
                        families = data.families,
                    ) {
                        branchFocusId = it
                        showFocusPicker = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFocusPicker = false }) { Text(i18n.s("cancel")) }
            },
        )
    }

    // 背景样式选择
    if (showBgPicker) {
        ModalBottomSheet(onDismissRequest = { showBgPicker = false }) {
            Text(
                i18n.s("bg_sheet_title"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            TreeBackgrounds.forEachIndexed { i, style ->
                val selected = i == viewModel.bgStyle.mod(TreeBackgrounds.size)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.changeBgStyle(i)
                            showBgPicker = false
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.verticalGradient(listOf(style.top, style.bottom)))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(width = 22.dp, height = 10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(PersonPalette[0]),
                        )
                    }
                    Text(
                        i18n.s("bg_${i + 1}"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    )
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 新建家族
    if (showNewFamily) {
        NewFamilyDialog(
            viewModel = viewModel,
            onDismiss = { showNewFamily = false },
            onCreated = { id ->
                showNewFamily = false
                viewModel.treeFamilyFilter = id
            },
        )
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ZoomFab(icon: ImageVector, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
    }
}
