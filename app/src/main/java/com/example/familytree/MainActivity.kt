package com.example.familytree

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.I18n
import com.example.familytree.data.Langs
import com.example.familytree.data.LanguageStore
import com.example.familytree.ui.AddRelationDialog
import com.example.familytree.ui.MembersScreen
import com.example.familytree.ui.PersonEditScreen
import com.example.familytree.ui.RelationsScreen
import com.example.familytree.ui.SettingsScreen
import com.example.familytree.ui.TextImportScreen
import com.example.familytree.ui.TransferScreen
import com.example.familytree.ui.TreeScreen
import com.example.familytree.ui.theme.FamilyTreeTheme
import com.example.familytree.ui.theme.LiquidGlassBackground
import com.example.familytree.ui.theme.LocalI18n
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

enum class MainTab(val labelKey: String, val icon: ImageVector) {
    Members("tab_members", Icons.Outlined.Groups),
    Relations("tab_relations", Icons.Outlined.Link),
    Tree("tab_tree", Icons.Outlined.AccountTree),
    Data("tab_data", Icons.Outlined.DataObject),
    Settings("tab_settings", Icons.Outlined.Settings),
}

/** 磨砂玻璃外壳：半透明渐变（整体约 72%~86% 不透明度）+ 顶部高光 + 1px 半透明描边 + 柔和投影 */
private fun Modifier.floatingGlassShell(dark: Boolean, shape: Shape): Modifier {
    val base = if (dark) Color(0xFF1C1C22) else Color.White
    return this
        .shadow(
            elevation = 14.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.08f),
            spotColor = Color.Black.copy(alpha = 0.14f),
        )
        .background(
            brush = Brush.verticalGradient(
                listOf(base.copy(alpha = 0.86f), base.copy(alpha = 0.72f)),
            ),
            shape = shape,
        )
        .background(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.22f),
                0.35f to Color.White.copy(alpha = 0.06f),
                1f to Color.Transparent,
            ),
            shape = shape,
        )
        .border(1.dp, Color.White.copy(alpha = if (dark) 0.14f else 0.30f), shape)
}

/**
 * 悬浮底部导航岛（Telegram 灵感 × 液态玻璃）：
 * 全宽玻璃胶囊、两端半圆、内容隐约透出；
 * 选中项为滑动的半透明圆角胶囊——位置用 onGloballyPositioned 实测每个标签的中心，
 * 保证在任何屏幕宽度下都与图标/文字严格居中；切换时 260ms ease-out 平滑滑动。
 */
@Composable
private fun FloatingGlassTabBar(
    current: MainTab,
    i18n: I18n,
    dark: Boolean,
    onSelect: (MainTab) -> Unit,
) {
    val density = LocalDensity.current
    val pillWidthPx = with(density) { 64.dp.roundToPx() }
    var shellLeftPx by remember { mutableIntStateOf(0) }
    val centers = remember { mutableMapOf<MainTab, Int>() }
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .floatingGlassShell(dark, RoundedCornerShape(percent = 50))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .onGloballyPositioned { c ->
                    shellLeftPx = c.positionInWindow().x.roundToInt()
                },
        ) {
            val targetPx = (centers[current] ?: 0) - shellLeftPx - pillWidthPx / 2
            val pillX by animateIntAsState(
                targetValue = targetPx,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                label = "glassPill",
            )
            // 滑动的选中胶囊（位于图标行）
            Box(
                Modifier
                    .offset { IntOffset(pillX, 0) }
                    .size(width = 64.dp, height = 36.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainTab.entries.forEach { t ->
                    val selected = current == t
                    val label = i18n.s(t.labelKey)
                    // 英文等长标签自动缩字号，避免在固定宽度标签格内被截断
                    val labelSp = when {
                        label.length >= 9 -> 9.sp
                        label.length >= 7 -> 10.sp
                        else -> 11.sp
                    }
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.08f else 1f,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "tabScale",
                    )
                    val iconAlpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.58f,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "tabAlpha",
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "tabColor",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            // 关闭灰色水波纹暂留，只保留蓝色圆角胶囊的选中反馈
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(t) }
                            .onGloballyPositioned { c ->
                                centers[t] = c.positionInWindow().x.roundToInt() + c.size.width / 2
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(width = 64.dp, height = 36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                t.icon,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(23.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                        alpha = iconAlpha
                                    },
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label,
                            fontSize = labelSp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    /** 应用内语言：在附加 Context 前切换 Locale，使所有界面即时生效 */
    override fun attachBaseContext(newBase: Context) {
        val lang = LanguageStore.load(newBase)
        val ctx = if (lang == LanguageStore.FOLLOW) {
            newBase
        } else {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(lang))
            newBase.createConfigurationContext(config)
        }
        super.attachBaseContext(ctx)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val i18n = I18n(Langs.tableFor(LanguageStore.load(this)))
            CompositionLocalProvider(LocalI18n provides i18n) {
                FamilyTreeTheme {
                    AppContent()
                }
            }
        }
    }
}

@Composable
private fun AppContent() {
    val i18n = LocalI18n.current
    val viewModel: FamilyViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(MainTab.Members) }
    // 成员页批量移入家族的选择模式（提升到此处，选择模式下隐藏悬浮按钮避免遮挡）
    var membersSelectMode by remember { mutableStateOf(false) }
    // 成员编辑栈（空字符串表示新增）
    var editStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 文字导入界面
    var textImportOpen by remember { mutableStateOf(false) }
    // 全局「添加关系」对话框：relationPair = 拓扑图上点选的两个节点
    var relationPair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showRelationDialog by remember { mutableStateOf(false) }

    fun notify(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    if (textImportOpen) {
        BackHandler { textImportOpen = false }
        TextImportScreen(
            viewModel = viewModel,
            onBack = { textImportOpen = false },
        )
    } else if (editStack.isNotEmpty()) {
        BackHandler { editStack = editStack.dropLast(1) }
        PersonEditScreen(
            viewModel = viewModel,
            personId = editStack.last().ifEmpty { null },
            onBack = { editStack = editStack.dropLast(1) },
        )
    } else {
        val dark = isSystemInDarkTheme()
        Box(Modifier.fillMaxSize()) {
            LiquidGlassBackground(dark) {
                Scaffold(
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        FloatingGlassTabBar(
                            current = tab,
                            i18n = i18n,
                            dark = dark,
                            onSelect = { t ->
                                tab = t
                                // 离开成员页时退出选择模式
                                if (t != MainTab.Members) membersSelectMode = false
                            },
                        )
                    },
                    floatingActionButton = {
                        when (tab) {
                            MainTab.Members -> if (!membersSelectMode) {
                                FloatingActionButton(
                                    onClick = { editStack = listOf("") },
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Default.PersonAdd, null)
                                }
                            }
                            MainTab.Relations -> FloatingActionButton(
                                onClick = {
                                    relationPair = null
                                    showRelationDialog = true
                                },
                                shape = CircleShape,
                            ) {
                                Icon(Icons.Default.Add, null)
                            }
                            else -> Unit
                        }
                    },
                ) { innerPadding ->
                    Box(
                        Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                    ) {
                        when (tab) {
                            MainTab.Members -> MembersScreen(
                                viewModel = viewModel,
                                selectMode = membersSelectMode,
                                onSelectModeChange = { membersSelectMode = it },
                                onEdit = { id -> editStack = listOf(id) },
                                onTextImport = { textImportOpen = true },
                            )
                            MainTab.Relations -> RelationsScreen(viewModel)
                            MainTab.Tree -> TreeScreen(
                                viewModel = viewModel,
                                onEditPerson = { id -> editStack = listOf(id) },
                                onConnect = { a, b ->
                                    relationPair = a to b
                                    showRelationDialog = true
                                },
                            )
                            MainTab.Data -> TransferScreen(viewModel)
                            MainTab.Settings -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    if (showRelationDialog) {
        AddRelationDialog(
            viewModel = viewModel,
            presetA = relationPair?.first,
            presetB = relationPair?.second,
            onDismiss = { showRelationDialog = false },
            onResult = { ok ->
                showRelationDialog = false
                notify(if (ok) i18n.s("rel_added_toast") else i18n.s("rel_add_fail_toast"))
            },
        )
    }
}
