@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.Person
import com.example.familytree.data.Relation
import com.example.familytree.data.RelationType
import com.example.familytree.data.filteredBy
import com.example.familytree.data.personYears
import com.example.familytree.data.relationsFilteredBy
import com.example.familytree.data.typeLabelA
import com.example.familytree.ui.theme.LocalI18n

/** 关系页：全部亲属关系列表；支持按成员姓名搜索与按关系类型筛选；删除后家谱同步更新 */
@Composable
fun RelationsScreen(viewModel: FamilyViewModel) {
    val i18n = LocalI18n.current
    val data = viewModel.data
    val shown = data.filteredBy(viewModel.relFamilyFilter) // 用于成员统计
    // 关系列表：任一端属于所选家族即显示（跨家族的推断关系同样可搜索）
    val shownRelations = remember(data, viewModel.relFamilyFilter) {
        data.relationsFilteredBy(viewModel.relFamilyFilter)
    }
    val byId = data.persons.associateBy { it.id }
    var showNewFamily by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Relation?>(null) }
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var relTypeFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // 按关系类型搜索与筛选（列出对应的成员组合）
    val displayed = remember(shownRelations, query, relTypeFilter) {
        val q = query.trim()
        shownRelations.filter { r ->
            val typeOk = when (relTypeFilter) {
                null -> true
                "parent" -> r.type == RelationType.FATHER || r.type == RelationType.MOTHER ||
                    r.type == RelationType.SON || r.type == RelationType.DAUGHTER || r.type == RelationType.PARENT
                "spouse" -> r.type == RelationType.SPOUSE
                "sibling" -> r.type == RelationType.SIBLING
                "lianjin" -> r.type == RelationType.LIANJIN
                "zhouli" -> r.type == RelationType.ZHOULI
                "custom" -> r.type == RelationType.CUSTOM
                else -> true
            }
            // 关系类型标签（含自定义标签如 祖父/堂哥）匹配搜索词
            val labelOk = q.isEmpty() ||
                r.typeLabelA(byId, i18n).contains(q, ignoreCase = true)
            typeOk && labelOk
        }
    }
    val filtering = query.isNotBlank() || relTypeFilter != null

    Column(Modifier.fillMaxSize()) {
        GlassTopBar(
            title = { Text(i18n.s("relations_title")) },
            actions = {
                IconButton(onClick = {
                    val n = viewModel.refreshFamilyRelations(viewModel.relFamilyFilter)
                    val msg = when {
                        n > 0 -> i18n.s("refreshed_toast", n)
                        n < 0 -> i18n.s("refreshed_cleaned", -n)
                        else -> i18n.s("refreshed_none")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Refresh, i18n.s("refresh_relations"))
                }
                Text(
                    if (filtering) i18n.s("filtered_n", displayed.size)
                    else i18n.s("stats_people_relations", shown.persons.size, shownRelations.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 16.dp),
                )
            },
        )

        FamilyChipsRow(
            families = data.families,
            selection = viewModel.relFamilyFilter,
            onSelect = { viewModel.relFamilyFilter = it },
            onNew = { showNewFamily = true },
        )

        // 按关系类型搜索：输入关系名，列出对应的成员组合（与成员页搜索框同款胶囊风格）
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(i18n.s("search_relation_hint")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, i18n.s("clear"))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // 按关系类型筛选
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = relTypeFilter == null, onClick = { relTypeFilter = null }, label = { Text(i18n.s("family_all")) })
            FilterChip(selected = relTypeFilter == "parent", onClick = { relTypeFilter = "parent" }, label = { Text(i18n.s("rel_parent")) })
            FilterChip(selected = relTypeFilter == "spouse", onClick = { relTypeFilter = "spouse" }, label = { Text(i18n.s("rel_spouse")) })
            FilterChip(selected = relTypeFilter == "sibling", onClick = { relTypeFilter = "sibling" }, label = { Text(i18n.s("rel_sibling")) })
            FilterChip(selected = relTypeFilter == "lianjin", onClick = { relTypeFilter = "lianjin" }, label = { Text(i18n.s("l_lianjin")) })
            FilterChip(selected = relTypeFilter == "zhouli", onClick = { relTypeFilter = "zhouli" }, label = { Text(i18n.s("l_zhouli")) })
            FilterChip(selected = relTypeFilter == "custom", onClick = { relTypeFilter = "custom" }, label = { Text(i18n.s("rel_custom")) })
        }

        if (shownRelations.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Link,
                    null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    i18n.s("relations_empty_title"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    i18n.s("relations_empty_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else if (displayed.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    i18n.s("no_match_relations"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = displayed, key = { it.id }, contentType = { "relation" }) { r ->
                    RelationCard(r, byId, data.persons, i18n, onDelete = { pendingDelete = r })
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
                viewModel.relFamilyFilter = id
            },
        )
    }

    // 删除关系确认
    val delTarget = pendingDelete
    if (delTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(i18n.s("delete_relation_title")) },
            text = { Text(i18n.s("delete_relation_text")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelation(delTarget.id)
                    pendingDelete = null
                }) {
                    Text(i18n.s("delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(i18n.s("cancel")) }
            },
        )
    }
}

@Composable
private fun RelationCard(
    relation: Relation,
    byId: Map<String, Person>,
    allPersons: List<Person>,
    i18n: com.example.familytree.data.I18n,
    onDelete: () -> Unit,
) {
    val a = byId[relation.fromId] ?: return
    val b = byId[relation.toId] ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonAvatar(a, 36, allPersons)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    personYears(a, i18n),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TypeChip(relation.typeLabelA(byId, i18n))
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(b.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    personYears(b, i18n),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PersonAvatar(b, 36, allPersons)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    i18n.s("delete_relation"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
