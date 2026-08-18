@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.familytree.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.Gender
import com.example.familytree.data.Person
import com.example.familytree.data.familyNamesOf
import com.example.familytree.data.filteredBy
import com.example.familytree.data.personYears
import com.example.familytree.graph.TreeLayoutEngine
import com.example.familytree.ui.theme.LocalI18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun MembersScreen(
    viewModel: FamilyViewModel,
    selectMode: Boolean,
    onSelectModeChange: (Boolean) -> Unit,
    onEdit: (String) -> Unit,
    onTextImport: () -> Unit,
) {
    val i18n = LocalI18n.current
    val data = viewModel.data
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var showNewFamily by remember { mutableStateOf(false) }
    // 代际排序与性别筛选
    var sortMode by rememberSaveable { mutableStateOf("default") } // default / genDesc(老辈在前) / genAsc(小辈在前)
    var genderFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // 批量移入家族：已选成员（选择模式状态由外部管理，避免悬浮按钮遮挡操作栏）
    val selectedIds = remember { mutableStateListOf<String>() }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var moveTargetFamilyId by remember { mutableStateOf("") }
    val context = LocalContext.current

    fun toggleSelect(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun longPressSelect(id: String) {
        if (!selectMode) onSelectModeChange(true)
        toggleSelect(id)
    }

    // 列表状态：排序/筛选切换后自动回滚到顶部
    val listState = rememberLazyListState()
    LaunchedEffect(sortMode, genderFilter) {
        listState.scrollToItem(0)
    }

    val shown = remember(data, viewModel.familyFilter) { data.filteredBy(viewModel.familyFilter) }
    // 统计并入标题下方单行显示，为成员列表让出首页空间
    val stats = remember(shown) { if (shown.persons.isEmpty()) null else computeStats(shown.persons) }
    // 预计算每人关系数：卡片渲染 O(1) 查询，避免列表滚动时重复遍历全部关系
    val relationCounts = remember(data) {
        val map = HashMap<String, Int>()
        data.relations.forEach { r ->
            map[r.fromId] = (map[r.fromId] ?: 0) + 1
            map[r.toId] = (map[r.toId] ?: 0) + 1
        }
        map
    }
    // 代际计算（后台线程执行，避免点击排序按钮时阻塞 UI）
    val genOf by produceState<Map<String, Int>>(initialValue = emptyMap(), shown) {
        value = if (shown.persons.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) {
                val l = TreeLayoutEngine.layout(shown)
                l.boxes.mapValues {
                    ((it.value.top - TreeLayoutEngine.PAD) / TreeLayoutEngine.ROW_H).roundToInt().coerceAtLeast(0)
                }
            }
        }
    }
    // 性别筛选 + 代际排序后的成员列表
    val displayPersons = remember(shown, sortMode, genderFilter) {
        val filtered = shown.persons.filter { p ->
            when (genderFilter) {
                "male" -> p.gender == Gender.MALE
                "female" -> p.gender == Gender.FEMALE
                "unknown" -> p.gender == Gender.UNKNOWN
                else -> true
            }
        }
        when (sortMode) {
            "genDesc" -> filtered.sortedBy { genOf[it.id] ?: Int.MAX_VALUE }
            "genAsc" -> filtered.sortedByDescending { genOf[it.id] ?: Int.MIN_VALUE }
            else -> filtered
        }
    }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar(
            title = {
                Column {
                    Text(i18n.s("app_name"))
                    if (stats != null) {
                        Text(
                            buildList {
                                add(i18n.s("stats_total", stats.total))
                                add(i18n.s("stats_male", stats.male))
                                add(i18n.s("stats_female", stats.female))
                                add(if (stats.avgAge != null) i18n.s("stats_avg_age", stats.avgAge) else i18n.s("stats_avg_na"))
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                if (data.persons.isNotEmpty()) {
                    IconButton(onClick = {
                        onSelectModeChange(!selectMode)
                        selectedIds.clear()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.DriveFileMove,
                            i18n.s("move_to_family"),
                            tint = if (selectMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onTextImport) {
                    Icon(Icons.Default.AutoAwesome, i18n.s("text_import_title"))
                }
            },
        )

        FamilyChipsRow(
            families = data.families,
            selection = viewModel.familyFilter,
            onSelect = { viewModel.familyFilter = it },
            onNew = { showNewFamily = true },
        )

        // 代际排序 + 性别筛选
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = sortMode == "default", onClick = { sortMode = "default" }, label = { Text(i18n.s("sort_default")) })
            FilterChip(selected = sortMode == "genDesc", onClick = { sortMode = "genDesc" }, label = { Text(i18n.s("sort_gen_desc")) })
            FilterChip(selected = sortMode == "genAsc", onClick = { sortMode = "genAsc" }, label = { Text(i18n.s("sort_gen_asc")) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = genderFilter == null, onClick = { genderFilter = null }, label = { Text(i18n.s("family_all")) })
            FilterChip(selected = genderFilter == "male", onClick = { genderFilter = "male" }, label = { Text(i18n.s("male")) })
            FilterChip(selected = genderFilter == "female", onClick = { genderFilter = "female" }, label = { Text(i18n.s("female")) })
            FilterChip(selected = genderFilter == "unknown", onClick = { genderFilter = "unknown" }, label = { Text(i18n.s("unknown")) })
        }

        if (data.persons.isEmpty()) {
            EmptyMembers(
                i18n = i18n,
                onSample = viewModel::loadSample,
                onTextImport = onTextImport,
            )
        } else if (shown.persons.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.FamilyRestroom,
                    null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    i18n.s("family_empty_title"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    i18n.s("family_empty_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { searchActive = false },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        placeholder = { Text(i18n.s("search_member")) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, i18n.s("clear"))
                                }
                            }
                        },
                    )
                },
                expanded = searchActive,
                onExpandedChange = { searchActive = it },
                shape = RoundedCornerShape(16.dp),
                // 取消默认系统栏内边距，避免搜索框与筛选行/列表之间出现空白
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                val results = displayPersons.filter { it.name.contains(query.trim(), ignoreCase = true) }
                Column(
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    results.forEach { p ->
                        MemberCard(
                            person = p,
                            i18n = i18n,
                            relationCounts = relationCounts,
                            familyNames = data.familyNamesOf(p),
                            allPersons = data.persons,
                            selectMode = selectMode,
                            checked = p.id in selectedIds,
                            onClick = { if (selectMode) toggleSelect(p.id) else onEdit(p.id) },
                            onLongPress = { longPressSelect(p.id) },
                        )
                    }
                    if (results.isEmpty()) {
                        Text(
                            i18n.s("no_match"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            val filtered = displayPersons.filter { it.name.contains(query.trim(), ignoreCase = true) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.id }, contentType = { "member" }) { person ->
                    // 稳定化卡片参数：点击/长按 lambda 记忆化，避免排序切换时全部卡片重组
                    val click = remember(person.id, selectMode) {
                        { if (selectMode) toggleSelect(person.id) else onEdit(person.id) }
                    }
                    val longClick = remember(person.id) {
                        { longPressSelect(person.id) }
                    }
                    val famNames = remember(person.id, person.familyIds, data.families) {
                        data.familyNamesOf(person)
                    }
                    MemberCard(
                        person = person,
                        i18n = i18n,
                        relationCounts = relationCounts,
                        familyNames = famNames,
                        allPersons = data.persons,
                        selectMode = selectMode,
                        checked = person.id in selectedIds,
                        onClick = click,
                        onLongPress = longClick,
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            i18n.s("no_match"),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 选择模式底部操作栏（长按成员或点右上角图标进入）——白色圆角悬浮卡片风格
            if (selectMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (selectedIds.isEmpty()) i18n.s("select_hint") else i18n.s("selected_n", selectedIds.size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                onSelectModeChange(false)
                                selectedIds.clear()
                            }) {
                                Text(
                                    i18n.s("cancel"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { showDeleteSelectedConfirm = true },
                                enabled = selectedIds.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(i18n.s("delete"))
                            }
                            Button(
                                onClick = {
                                    moveTargetFamilyId = viewModel.defaultFamiliesForNew().firstOrNull()
                                        ?: data.families.firstOrNull()?.id ?: ""
                                    showMoveDialog = true
                                },
                                enabled = selectedIds.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text(i18n.s("move_to_family")) }
                        }
                    }
                }
            }
        }
    }

    if (showNewFamily) {
        NewFamilyDialog(
            viewModel = viewModel,
            onDismiss = { showNewFamily = false },
            onCreated = { id ->
                showNewFamily = false
                viewModel.familyFilter = id
            },
        )
    }

    // 批量移入家族对话框
    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(i18n.s("move_to_family")) },
            text = {
                Column {
                    Text(i18n.s("move_confirm_text", selectedIds.size), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    FamilyPicker(
                        label = i18n.s("target_family"),
                        families = data.families,
                        selectedId = moveTargetFamilyId,
                        includeNone = false,
                    ) { moveTargetFamilyId = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        viewModel.movePersonsToFamily(selectedIds.toList(), moveTargetFamilyId)
                        val targetName = data.families.firstOrNull { it.id == moveTargetFamilyId }?.name ?: i18n.s("family_none")
                        Toast.makeText(context, i18n.s("moved_toast", selectedIds.size, targetName), Toast.LENGTH_SHORT).show()
                        showMoveDialog = false
                        onSelectModeChange(false)
                        selectedIds.clear()
                    },
                ) { Text(i18n.s("move")) }
            },
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text(i18n.s("cancel")) }
            },
        )
    }

    // 批量删除确认
    if (showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text(i18n.s("delete_selected_title")) },
            text = { Text(i18n.s("delete_selected_text", selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePersons(selectedIds.toList())
                    Toast.makeText(context, i18n.s("deleted_toast", selectedIds.size), Toast.LENGTH_SHORT).show()
                    showDeleteSelectedConfirm = false
                    onSelectModeChange(false)
                    selectedIds.clear()
                }) {
                    Text(i18n.s("delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) { Text(i18n.s("cancel")) }
            },
        )
    }
}

/** 家族统计 */
private data class FamilyStats(
    val total: Int,
    val male: Int,
    val female: Int,
    val unknown: Int,
    val alive: Int,
    val deceased: Int,
    val avgAge: Int?,
)

private fun computeStats(persons: List<Person>): FamilyStats {
    var male = 0
    var female = 0
    var unknown = 0
    var alive = 0
    var deceased = 0
    var withBirth = 0
    var ageSum = 0L
    val yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    persons.forEach { p ->
        when (p.gender) {
            Gender.MALE -> male++
            Gender.FEMALE -> female++
            Gender.UNKNOWN -> unknown++
        }
        if (p.death.isBlank()) alive++ else deceased++
        Regex("\\d{4}").find(p.birth)?.value?.toIntOrNull()?.let { y ->
            withBirth++
            ageSum += (yearNow - y).coerceIn(0, 150)
        }
    }
    return FamilyStats(
        total = persons.size,
        male = male,
        female = female,
        unknown = unknown,
        alive = alive,
        deceased = deceased,
        avgAge = if (withBirth > 0) (ageSum / withBirth).toInt() else null,
    )
}

@Composable
private fun EmptyMembers(
    i18n: com.example.familytree.data.I18n,
    onSample: () -> Unit,
    onTextImport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.FamilyRestroom,
            null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            i18n.s("empty_members_title"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            i18n.s("empty_members_hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSample) { Text(i18n.s("load_sample")) }
            Button(onClick = onTextImport) { Text(i18n.s("text_import_btn")) }
        }
    }
}

@Composable
private fun MemberCard(
    person: Person,
    i18n: com.example.familytree.data.I18n,
    relationCounts: Map<String, Int>,
    familyNames: List<String>,
    allPersons: List<Person>,
    selectMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 自定义长按手势：允许手指轻微移动（累计 24dp，远大于系统 8dp 阈值），
            // 长按约 400ms 即触发，杜绝“长按被当成滑动取消”的失灵与等待卡顿感
            .pointerInput(person.id, selectMode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalMove = 0f
                    var longPressed = false
                    val longPressTime = (viewConfiguration.longPressTimeoutMillis - 100L).coerceAtLeast(300L)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break // 抬起
                        totalMove += change.positionChange().getDistance()
                        if (totalMove > 24f) break // 移动过大 → 交给列表滚动
                        if (change.uptimeMillis - down.uptimeMillis >= longPressTime) {
                            longPressed = true
                            change.consume()
                            break
                        }
                    }
                    when {
                        longPressed -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                            // 消费余下事件直到抬起，避免误触点击
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                c.consume()
                            }
                        }
                        totalMove <= 8f -> onClick() // 轻点 → 查看/编辑
                        else -> Unit // 微小拖动 → 视为滚动意图
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 复选框平滑展开/收起，避免进入多选时卡片布局跳变
            AnimatedVisibility(
                visible = selectMode,
                enter = expandHorizontally(animationSpec = tween(160)),
                exit = shrinkHorizontally(animationSpec = tween(160)),
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                )
            }
            PersonAvatar(person, 48, allPersons)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (familyNames.isNotEmpty()) {
                        Text(
                            " · " + familyNames.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                val sub = buildList {
                    when (person.gender) {
                        Gender.MALE -> add(i18n.s("male"))
                        Gender.FEMALE -> add(i18n.s("female"))
                        Gender.UNKNOWN -> Unit
                    }
                    personYears(person, i18n).takeIf { it.isNotBlank() }?.let { add(it) }
                    add(i18n.s("relations_count_n", relationCounts[person.id] ?: 0))
                }.joinToString(" · ")
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
