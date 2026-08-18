@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.familytree.data.FAMILY_NONE
import com.example.familytree.data.Family
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.Gender
import com.example.familytree.data.Person
import com.example.familytree.ui.theme.LocalI18n
import com.example.familytree.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 节点配色：前三个为 男/女/未知 的默认色 */
val PersonPalette = listOf(
    Color(0xFF1976D2), // 蓝（男）
    Color(0xFFD81B60), // 粉（女）
    Color(0xFF616161), // 灰（未知）
    Color(0xFF388E3C),
    Color(0xFF7B1FA2),
    Color(0xFFF57C00),
    Color(0xFF00796B),
    Color(0xFFC62828),
    Color(0xFF5D4037),
    Color(0xFF283593),
)

/**
 * 节点颜色：
 *  - 手动指定 colorIndex 优先；
 *  - 同名成员（不同人）自动分配不同颜色，便于区分；
 *  - 其余按性别取默认色。
 */
fun personColor(p: Person, allPersons: List<Person>? = null): Color {
    p.colorIndex?.let { return PersonPalette[it.mod(PersonPalette.size)] }
    val same = allPersons?.filter { it.name == p.name }
    if (same != null && same.size > 1) {
        val idx = same.indexOfFirst { it.id == p.id }
        val base = 3
        val span = PersonPalette.size - base
        return PersonPalette[base + idx.mod(span)]
    }
    return when (p.gender) {
        Gender.MALE -> PersonPalette[0]
        Gender.FEMALE -> PersonPalette[1]
        Gender.UNKNOWN -> PersonPalette[2]
    }
}

/** 圆形首字头像 */
@Composable
fun PersonAvatar(person: Person, size: Int = 44, allPersons: List<Person>? = null) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(personColor(person, allPersons)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = person.name.take(1).ifBlank { "?" },
            color = Color.White,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 小节标题 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 顶部栏（参考图风格）：扁平、无面板，大号加粗标题 + 灰色操作图标 */
@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon()
        Box(Modifier.weight(1f)) {
            ProvideTextStyle(
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            ) {
                title()
            }
        }
        actions()
    }
}

/** 圆角关系徽标（如「父亲」「配偶」）：浅蓝底 + 蓝字（参考图选中态配色） */
@Composable
fun TypeChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 家族筛选条：全部 / 未分组 / 各家族 / ＋新建家族（可附加尾部操作，如刷新） */
@Composable
fun FamilyChipsRow(
    families: List<Family>,
    selection: String?,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    val i18n = LocalI18n.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = selection == null, onClick = { onSelect(null) }, label = { Text(i18n.s("family_all")) })
        FilterChip(
            selected = selection == FAMILY_NONE,
            onClick = { onSelect(FAMILY_NONE) },
            label = { Text(i18n.s("family_none")) },
        )
        families.forEach { f ->
            FilterChip(
                selected = selection == f.id,
                onClick = { onSelect(f.id) },
                label = { Text(f.name) },
            )
        }
        AssistChip(onClick = onNew, label = { Text(i18n.s("family_new")) })
        trailing?.invoke()
    }
}

/** 新建家族对话框 */
@Composable
fun NewFamilyDialog(
    viewModel: FamilyViewModel,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val i18n = LocalI18n.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n.s("new_family_title")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(i18n.s("new_family_name_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val f = viewModel.addFamily(name)
                    onCreated(f.id)
                },
            ) { Text(i18n.s("create")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(i18n.s("cancel")) }
        },
    )
}

/** 照片缩略图（异步解码，失败时显示占位） */
@Composable
fun PhotoThumb(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** 家族下拉选择器（includeNone=true 时含「未分组」选项） */
@Composable
fun FamilyPicker(
    label: String,
    families: List<Family>,
    selectedId: String,
    includeNone: Boolean = true,
    onSelect: (String) -> Unit,
) {
    val i18n = LocalI18n.current
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = i18n.s("family_none")
    val options = if (includeNone) {
        listOf("" to noneLabel) + families.map { it.id to it.name }
    } else {
        families.map { it.id to it.name }
    }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
        ?: options.firstOrNull()?.second ?: ""
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
