@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.Gender
import com.example.familytree.data.KinshipInference
import com.example.familytree.data.Relation
import com.example.familytree.data.RelationType
import com.example.familytree.data.describe
import com.example.familytree.ui.theme.LocalI18n
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PersonEditScreen(
    viewModel: FamilyViewModel,
    personId: String?,
    onBack: () -> Unit,
) {
    val i18n = LocalI18n.current
    val data = viewModel.data
    val person = personId?.let { id -> data.persons.firstOrNull { it.id == id } }
    val isNew = person == null

    var name by rememberSaveable { mutableStateOf(person?.name ?: "") }
    var gender by rememberSaveable { mutableStateOf(person?.gender ?: Gender.UNKNOWN) }
    var birth by rememberSaveable { mutableStateOf(person?.birth ?: "") }
    var death by rememberSaveable { mutableStateOf(person?.death ?: "") }
    var notes by rememberSaveable { mutableStateOf(person?.notes ?: "") }
    var colorIndex by rememberSaveable { mutableStateOf(person?.colorIndex) }
    var familyIds by rememberSaveable { mutableStateOf(person?.familyIds ?: viewModel.defaultFamiliesForNew()) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteRel by remember { mutableStateOf<Relation?>(null) }
    var relationDialog by remember { mutableStateOf(false) }
    var presetA by remember { mutableStateOf<String?>(null) }
    var presetB by remember { mutableStateOf<String?>(null) }
    var presetType by remember { mutableStateOf<RelationType?>(null) }
    var editRelationId by remember { mutableStateOf<String?>(null) }

    // 新增成员时直接关联已有成员
    var linkTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkType by rememberSaveable { mutableStateOf<RelationType?>(null) }
    var linkCustom by rememberSaveable { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 成员照片（最多 3 张）
    var viewPhotoFile by remember { mutableStateOf<File?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        val p = person ?: return@rememberLauncherForActivityResult
        uris.take(3 - p.photos.size).forEach { uri ->
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) viewModel.addPhoto(p.id, bytes)
        }
    }

    fun relLabel(t: RelationType): String = when (t) {
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

    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (personId == null) {
            val created = viewModel.addPerson(trimmed, gender, birth, death, notes, colorIndex, familyIds)
            // 保存后立即建立所选亲属关系
            val targetId = linkTargetId
            val t = linkType
            if (targetId != null && t != null) {
                when (t) {
                    RelationType.FATHER -> viewModel.addRelation(RelationType.FATHER, created.id, targetId)
                    RelationType.MOTHER -> viewModel.addRelation(RelationType.MOTHER, created.id, targetId)
                    RelationType.SON -> viewModel.addRelation(RelationType.SON, created.id, targetId)
                    RelationType.DAUGHTER -> viewModel.addRelation(RelationType.DAUGHTER, created.id, targetId)
                    RelationType.SPOUSE -> viewModel.addRelation(RelationType.SPOUSE, created.id, targetId)
                    RelationType.SIBLING -> viewModel.addRelation(RelationType.SIBLING, created.id, targetId)
                    RelationType.LIANJIN -> viewModel.addRelation(RelationType.LIANJIN, created.id, targetId)
                    RelationType.ZHOULI -> viewModel.addRelation(RelationType.ZHOULI, created.id, targetId)
                    RelationType.CUSTOM -> viewModel.addRelation(RelationType.CUSTOM, created.id, targetId, linkCustom.trim())
                    RelationType.PARENT -> Unit
                }
                // 提示到「关系」页刷新补全所有关系
                Toast.makeText(context, i18n.s("after_add_hint"), Toast.LENGTH_LONG).show()
            }
        } else if (person != null) {
            viewModel.updatePerson(
                person.copy(
                    name = trimmed,
                    gender = gender,
                    birth = birth.trim(),
                    death = death.trim(),
                    notes = notes.trim(),
                    colorIndex = colorIndex,
                    familyIds = familyIds,
                ),
            )
        }
        onBack()
    }

    fun openRelationDialog(a: String?, b: String?, t: RelationType?, editId: String? = null) {
        presetA = a
        presetB = b
        presetType = t
        editRelationId = editId
        relationDialog = true
    }

    Scaffold(
        topBar = {
            GlassTopBar(
                title = { Text(if (isNew) i18n.s("person_add_title") else i18n.s("person_edit_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, i18n.s("back"))
                    }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, i18n.s("delete"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 保存按钮固定在底部，无需滚动即可找到
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Button(
                    onClick = ::save,
                    enabled = name.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(48.dp),
                ) {
                    Text(
                        if (isNew) i18n.s("save_add") else i18n.s("save"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(i18n.s("name_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text(i18n.s("gender_label"), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenderOption(i18n.s("male"), Gender.MALE, gender) { gender = it }
                GenderOption(i18n.s("female"), Gender.FEMALE, gender) { gender = it }
                GenderOption(i18n.s("unknown"), Gender.UNKNOWN, gender) { gender = it }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = birth,
                    onValueChange = { birth = it },
                    label = { Text(i18n.s("birth_label")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = death,
                    onValueChange = { death = it },
                    label = { Text(i18n.s("death_label")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(i18n.s("notes_label")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(i18n.s("node_color"), style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorDot(color = null, selected = colorIndex == null, label = i18n.s("auto")) { colorIndex = null }
                PersonPalette.forEachIndexed { i, c ->
                    ColorDot(color = c, selected = colorIndex == i, label = null) { colorIndex = i }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(i18n.s("family_label"), style = MaterialTheme.typography.labelLarge)
            if (data.families.isEmpty()) {
                Text(
                    i18n.s("no_families"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    data.families.forEach { f ->
                        FilterChip(
                            selected = f.id in familyIds,
                            onClick = {
                                familyIds = if (f.id in familyIds) familyIds - f.id else familyIds + f.id
                            },
                            label = { Text(f.name) },
                        )
                    }
                }
                Text(
                    i18n.s("family_multi_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // 成员照片（最多 3 张）
            if (person != null) {
                Spacer(Modifier.height(16.dp))
                Text(i18n.s("photo_section"), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    person.photos.take(3).forEach { name ->
                        val file = viewModel.photoFile(name)
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            PhotoThumb(
                                file = file,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { viewPhotoFile = file },
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable { viewModel.removePhoto(person.id, name) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    if (person.photos.size < 3) {
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Add, null)
                        }
                    }
                }
                Text(
                    i18n.s("photo_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // 新增成员：直接选择与已有成员的亲属关系
            if (isNew && data.persons.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                SectionTitle(i18n.s("optional_relation_title"))
                Spacer(Modifier.height(4.dp))
                Text(
                    i18n.s("optional_relation_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PersonPicker(
                    label = i18n.s("member_b"),
                    persons = data.persons,
                    selectedId = linkTargetId,
                    families = data.families,
                ) { linkTargetId = it }

                if (linkTargetId != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(i18n.s("new_is_x"), style = MaterialTheme.typography.labelLarge)
                    val options = when (gender) {
                        Gender.MALE -> listOf(RelationType.FATHER, RelationType.SON, RelationType.SPOUSE, RelationType.SIBLING, RelationType.CUSTOM)
                        Gender.FEMALE -> listOf(RelationType.MOTHER, RelationType.DAUGHTER, RelationType.SPOUSE, RelationType.SIBLING, RelationType.CUSTOM)
                        Gender.UNKNOWN -> listOf(
                            RelationType.FATHER, RelationType.MOTHER, RelationType.SON, RelationType.DAUGHTER,
                            RelationType.SPOUSE, RelationType.SIBLING, RelationType.CUSTOM,
                        )
                    }
                    Row(
                        Modifier
                            .padding(top = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { t ->
                            FilterChip(
                                selected = linkType == t,
                                onClick = { linkType = t },
                                label = { Text(relLabel(t)) },
                            )
                        }
                    }
                    if (linkType == RelationType.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = linkCustom,
                            onValueChange = { linkCustom = it },
                            label = { Text(i18n.s("custom_label_hint")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (person != null) {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(i18n.s("relations_section"))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { openRelationDialog(null, null, null) }) {
                        Text(i18n.s("add_relation"))
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { openRelationDialog(person.id, null, RelationType.SPOUSE) },
                        label = { Text(i18n.s("chip_spouse")) },
                    )
                    AssistChip(
                        onClick = { openRelationDialog(null, person.id, RelationType.FATHER) },
                        label = { Text(i18n.s("chip_father")) },
                    )
                    AssistChip(
                        onClick = { openRelationDialog(null, person.id, RelationType.MOTHER) },
                        label = { Text(i18n.s("chip_mother")) },
                    )
                }
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { openRelationDialog(null, person.id, RelationType.SON) },
                        label = { Text(i18n.s("chip_son")) },
                    )
                    AssistChip(
                        onClick = { openRelationDialog(null, person.id, RelationType.DAUGHTER) },
                        label = { Text(i18n.s("chip_daughter")) },
                    )
                    AssistChip(
                        onClick = { openRelationDialog(person.id, null, RelationType.SIBLING) },
                        label = { Text(i18n.s("chip_sibling")) },
                    )
                }
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { openRelationDialog(person.id, null, RelationType.LIANJIN) },
                        label = { Text(i18n.s("chip_lianjin")) },
                    )
                    AssistChip(
                        onClick = { openRelationDialog(person.id, null, RelationType.ZHOULI) },
                        label = { Text(i18n.s("chip_zhouli")) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                val mine = data.relations.filter { it.fromId == person.id || it.toId == person.id }
                if (mine.isEmpty()) {
                    Text(
                        i18n.s("no_relations_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val byId = data.persons.associateBy { it.id }
                    mine.forEach { r ->
                        Card(
                            onClick = { openRelationDialog(r.fromId, r.toId, r.type, r.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    r.describe(person.id, byId, i18n, data),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    i18n.s("edit_hint"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                IconButton(onClick = {
                                    pendingDeleteRel = r
                                }) {
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
                }

                Text(
                    i18n.s("relation_tip"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                // 跨代关系自动推断（祖孙、叔伯、舅姨、侄甥、孙辈等血缘跨代）
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                SectionTitle(i18n.s("derived_title"))
                Spacer(Modifier.height(4.dp))
                val derived = remember(data, i18n.table) { KinshipInference.derive(person, data, i18n) }
                val crossGenCats = setOf(1, 2, 5, 8)
                val crossGen = derived.filter { it.category in crossGenCats }
                val others = derived.filter { it.category !in crossGenCats }

                if (crossGen.isEmpty()) {
                    Text(
                        i18n.s("derived_empty_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    crossGen.forEach { d ->
                        val other = data.persons.firstOrNull { it.id == d.otherId } ?: return@forEach
                        DerivedRow(d.label, other.name)
                    }
                }

                // 自动推断的亲属关系（同代与姻亲）
                Spacer(Modifier.height(20.dp))
                SectionTitle(i18n.s("derived_other_title"))
                Spacer(Modifier.height(4.dp))
                if (others.isEmpty()) {
                    Text(
                        i18n.s("derived_other_empty_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    others.forEach { d ->
                        val other = data.persons.firstOrNull { it.id == d.otherId } ?: return@forEach
                        DerivedRow(d.label, other.name)
                    }
                }
                if (derived.isNotEmpty()) {
                    Text(
                        i18n.s("derived_note"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    if (relationDialog && person != null) {
        AddRelationDialog(
            viewModel = viewModel,
            presetA = presetA,
            presetB = presetB,
            presetType = presetType,
            editRelationId = editRelationId,
            onDismiss = { relationDialog = false },
            onResult = { ok ->
                relationDialog = false
                scope.launch {
                    snackbar.showSnackbar(if (ok) i18n.s("rel_added_toast") else i18n.s("rel_add_fail_toast"))
                }
            },
        )
    }

    if (showDeleteConfirm && person != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(i18n.s("delete_member_title")) },
            text = { Text(i18n.s("delete_member_text", person.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deletePerson(person.id)
                    onBack()
                }) {
                    Text(i18n.s("delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(i18n.s("cancel")) }
            },
        )
    }

    // 删除关系确认
    val delRel = pendingDeleteRel
    if (delRel != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteRel = null },
            title = { Text(i18n.s("delete_relation_title")) },
            text = { Text(i18n.s("delete_relation_text")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelation(delRel.id)
                    pendingDeleteRel = null
                }) {
                    Text(i18n.s("delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRel = null }) { Text(i18n.s("cancel")) }
            },
        )
    }

    // 照片大图查看
    val photoFile = viewPhotoFile
    if (photoFile != null) {
        Dialog(onDismissRequest = { viewPhotoFile = null }) {
            Card {
                Column(
                    Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(i18n.s("photo_view"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    PhotoThumb(
                        file = photoFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    TextButton(onClick = { viewPhotoFile = null }, modifier = Modifier.padding(top = 6.dp)) {
                        Text(i18n.s("cancel"))
                    }
                }
            }
        }
    }
}

@Composable
private fun DerivedRow(label: String, name: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeChip(label)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GenderOption(label: String, value: Gender, current: Gender, onSelect: (Gender) -> Unit) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}

@Composable
private fun ColorDot(color: Color?, selected: Boolean, label: String?, onClick: () -> Unit) {
    val bg = color ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
