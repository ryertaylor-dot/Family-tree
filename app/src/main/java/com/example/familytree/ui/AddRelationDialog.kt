@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.familytree.data.FAMILY_NONE
import com.example.familytree.data.Family
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.Person
import com.example.familytree.data.RelationType
import com.example.familytree.data.personYears
import com.example.familytree.ui.theme.LocalI18n

/**
 * 通用「添加亲属关系」对话框：
 * 选择成员 A、关系类型（A 是 B 的 …）、成员 B，确认后家谱自动更新。
 *
 * @param presetA/presetB/presetType 预设（例如拓扑图上点选的两个节点）
 */
@Composable
fun AddRelationDialog(
    viewModel: FamilyViewModel,
    presetA: String? = null,
    presetB: String? = null,
    presetType: RelationType? = null,
    editRelationId: String? = null,
    onDismiss: () -> Unit,
    onResult: (Boolean) -> Unit,
) {
    val i18n = LocalI18n.current
    val persons = viewModel.data.persons
    val editing = editRelationId?.let { id -> viewModel.data.relations.firstOrNull { it.id == id } }
    val typeOptions = listOf(
        RelationType.SPOUSE,
        RelationType.FATHER,
        RelationType.MOTHER,
        RelationType.SON,
        RelationType.DAUGHTER,
        RelationType.SIBLING,
        RelationType.LIANJIN,
        RelationType.ZHOULI,
        RelationType.CUSTOM,
    )
    fun typeLabel(t: RelationType): String = when (t) {
        RelationType.SPOUSE -> i18n.s("rel_spouse")
        RelationType.FATHER -> i18n.s("rel_father")
        RelationType.MOTHER -> i18n.s("rel_mother")
        RelationType.SON -> i18n.s("rel_son")
        RelationType.DAUGHTER -> i18n.s("rel_daughter")
        RelationType.SIBLING -> i18n.s("rel_sibling")
        RelationType.LIANJIN -> i18n.s("l_lianjin")
        RelationType.ZHOULI -> i18n.s("l_zhouli")
        RelationType.CUSTOM -> i18n.s("rel_custom")
        RelationType.PARENT -> i18n.s("l_parent")
    }

    var aId by remember { mutableStateOf(editing?.fromId ?: presetA) }
    var bId by remember { mutableStateOf(editing?.toId ?: presetB) }
    var selectedType by remember { mutableStateOf(editing?.type ?: presetType ?: RelationType.SPOUSE) }
    var customLabel by remember { mutableStateOf(editing?.label ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) i18n.s("edit_relation") else i18n.s("relation_dialog_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (persons.size < 2) {
                    Text(
                        i18n.s("need_two_members"),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(i18n.s("type_label"), style = MaterialTheme.typography.labelLarge)
                    typeOptions.forEach { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedType = t }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedType == t, onClick = { selectedType = t })
                            Text(typeLabel(t))
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    PersonPicker(
                        label = i18n.s("member_a"),
                        persons = persons,
                        selectedId = aId,
                        families = viewModel.data.families,
                    ) { aId = it }

                    Spacer(Modifier.height(8.dp))
                    PersonPicker(
                        label = i18n.s("member_b"),
                        persons = persons.filter { it.id != aId },
                        selectedId = bId,
                        families = viewModel.data.families,
                    ) { bId = it }

                    if (selectedType == RelationType.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customLabel,
                            onValueChange = { customLabel = it },
                            label = { Text(i18n.s("custom_label_hint")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = aId != null && bId != null && aId != bId &&
                    (selectedType != RelationType.CUSTOM || customLabel.isNotBlank()),
                onClick = {
                    val a = aId ?: return@TextButton
                    val b = bId ?: return@TextButton
                    val ok = if (editing != null) {
                        viewModel.updateRelation(editing.id, selectedType, a, b, customLabel.trim())
                    } else {
                        when (selectedType) {
                            RelationType.FATHER -> viewModel.addRelation(RelationType.FATHER, a, b)
                            RelationType.MOTHER -> viewModel.addRelation(RelationType.MOTHER, a, b)
                            RelationType.SON -> viewModel.addRelation(RelationType.SON, a, b)
                            RelationType.DAUGHTER -> viewModel.addRelation(RelationType.DAUGHTER, a, b)
                            RelationType.SIBLING -> viewModel.addRelation(RelationType.SIBLING, a, b)
                            RelationType.LIANJIN -> viewModel.addRelation(RelationType.LIANJIN, a, b)
                            RelationType.ZHOULI -> viewModel.addRelation(RelationType.ZHOULI, a, b)
                            RelationType.CUSTOM -> viewModel.addRelation(RelationType.CUSTOM, a, b, customLabel.trim())
                            else -> viewModel.addRelation(RelationType.SPOUSE, a, b)
                        }
                    }
                    onResult(ok)
                },
            ) { Text(i18n.s("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(i18n.s("cancel")) }
        },
    )
}

/** 下拉选择成员：支持姓名搜索与家族筛选（带头像，可滚动） */
@Composable
fun PersonPicker(
    label: String,
    persons: List<Person>,
    selectedId: String?,
    families: List<Family> = emptyList(),
    onSelect: (String) -> Unit,
) {
    val i18n = LocalI18n.current
    var expanded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var familyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = persons.firstOrNull { it.id == selectedId }

    val filtered = persons.filter { p ->
        val matchFamily = when (familyFilter) {
            null -> true
            FAMILY_NONE -> p.familyIds.isEmpty()
            else -> p.familyId == familyFilter || familyFilter in p.familyIds
        }
        matchFamily && (query.isBlank() || p.name.contains(query.trim(), ignoreCase = true))
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: "",
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
            Column(Modifier.heightIn(max = 420.dp)) {
                // 搜索框
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(i18n.s("search_member")) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, i18n.s("clear"))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // 家族筛选
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = familyFilter == null, onClick = { familyFilter = null }, label = { Text(i18n.s("family_all")) })
                    FilterChip(
                        selected = familyFilter == FAMILY_NONE,
                        onClick = { familyFilter = FAMILY_NONE },
                        label = { Text(i18n.s("family_none")) },
                    )
                    families.forEach { f ->
                        FilterChip(
                            selected = familyFilter == f.id,
                            onClick = { familyFilter = f.id },
                            label = { Text(f.name) },
                        )
                    }
                }
                // 结果列表
                Column(
                    Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PersonAvatar(p, 26, persons)
                                    Spacer(Modifier.width(10.dp))
                                    Text(p.name)
                                    val years = personYears(p, i18n)
                                    if (years.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            years,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelect(p.id)
                                query = ""
                                expanded = false
                            },
                        )
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            i18n.s("no_match"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
