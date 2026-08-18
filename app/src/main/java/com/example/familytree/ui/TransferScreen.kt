@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.example.familytree.data.FamilyData
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.GedcomCodec
import com.example.familytree.data.ImportResult
import com.example.familytree.ui.theme.LocalI18n
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransferScreen(viewModel: FamilyViewModel) {
    val i18n = LocalI18n.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var showPasteDialog by remember { mutableStateOf(false) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var pendingGedcom by remember { mutableStateOf<FamilyData?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = viewModel.toJson()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            toast(i18n.s("export_ok"))
        } catch (e: Exception) {
            toast(i18n.s("export_fail", e.message ?: ""))
        }
    }

    val gedcomExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-gedcom"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val ged = GedcomCodec.export(viewModel.data)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ged.toByteArray(Charsets.UTF_8))
            }
            toast(i18n.s("gedcom_export_ok"))
        } catch (e: Exception) {
            toast(i18n.s("export_fail", e.message ?: ""))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.isEmpty()) {
                toast(i18n.s("file_empty"))
                return@rememberLauncherForActivityResult
            }
            val text = GedcomCodec.decode(bytes)
            if (GedcomCodec.looksLikeGedcom(text)) {
                val parsed = GedcomCodec.parse(text)
                if (parsed.persons.isEmpty()) {
                    toast(i18n.s("gedcom_parse_fail", ""))
                } else {
                    pendingGedcom = parsed
                }
            } else {
                if (text.isBlank()) {
                    toast(i18n.s("file_empty"))
                    return@rememberLauncherForActivityResult
                }
                pendingImportText = text
            }
        } catch (e: Exception) {
            toast(i18n.s("read_fail", e.message ?: ""))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(title = { Text(i18n.s("transfer_title")) })
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                SectionTitle(i18n.s("export_section"))
                Spacer(Modifier.height(8.dp))
                TransferButton(
                    icon = Icons.Default.UploadFile,
                    title = i18n.s("export_file"),
                    desc = i18n.s("export_file_desc"),
                    i18n = i18n,
                ) {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    exportLauncher.launch("家谱数据_$stamp.json")
                }
                TransferButton(
                    icon = Icons.Default.UploadFile,
                    title = i18n.s("export_gedcom"),
                    desc = i18n.s("export_gedcom_desc"),
                    i18n = i18n,
                ) {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    gedcomExportLauncher.launch("家谱数据_$stamp.ged")
                }
                TransferButton(
                    icon = Icons.Default.ContentCopy,
                    title = i18n.s("copy_json"),
                    desc = i18n.s("copy_json_desc"),
                    i18n = i18n,
                ) {
                    clipboard.setText(AnnotatedString(viewModel.toJson()))
                    toast(i18n.s("copied"))
                }

                Spacer(Modifier.height(24.dp))
                SectionTitle(i18n.s("import_section"))
                Spacer(Modifier.height(8.dp))
                TransferButton(
                    icon = Icons.Default.Download,
                    title = i18n.s("import_file"),
                    desc = i18n.s("import_file_desc"),
                    i18n = i18n,
                ) {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "text/*", "*/*"))
                }
                TransferButton(
                    icon = Icons.Default.Download,
                    title = i18n.s("import_gedcom"),
                    desc = i18n.s("import_gedcom_desc"),
                    i18n = i18n,
                ) {
                    importLauncher.launch(arrayOf("*/*"))
                }
                TransferButton(
                    icon = Icons.Default.EditNote,
                    title = i18n.s("paste_import"),
                    desc = i18n.s("paste_import_desc"),
                    i18n = i18n,
                ) {
                    showPasteDialog = true
                }

                Spacer(Modifier.height(24.dp))
                SectionTitle(i18n.s("data_section"))
                Spacer(Modifier.height(8.dp))
                TransferButton(
                    icon = Icons.Default.DeleteSweep,
                    title = i18n.s("clear_all"),
                    desc = i18n.s("clear_all_desc"),
                    i18n = i18n,
                    danger = true,
                ) {
                    showClearConfirm = true
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    i18n.s("current_data", viewModel.data.persons.size, viewModel.data.relations.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    i18n.s("import_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    // 粘贴 JSON 对话框
    if (showPasteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text(i18n.s("paste_title")) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(i18n.s("json_label")) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasteDialog = false
                        pendingImportText = text
                    },
                ) { Text(i18n.s("next_step")) }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text(i18n.s("cancel")) }
            },
        )
    }

    // 导入方式确认（替换 / 合并）—— JSON
    val importText = pendingImportText
    if (importText != null) {
        AlertDialog(
            onDismissRequest = { pendingImportText = null },
            title = { Text(i18n.s("import_title")) },
            text = { Text(i18n.s("import_prompt")) },
            confirmButton = {
                TextButton(onClick = {
                    val result = viewModel.importJson(importText, merge = false)
                    pendingImportText = null
                    toast(if (result is ImportResult.Success) i18n.s("import_replace_ok") else (result as ImportResult.Error).message)
                }) { Text(i18n.s("replace_data")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val result = viewModel.importJson(importText, merge = true)
                    pendingImportText = null
                    toast(if (result is ImportResult.Success) i18n.s("import_merge_ok") else (result as ImportResult.Error).message)
                }) { Text(i18n.s("merge_import")) }
            },
        )
    }

    // 导入方式确认（替换 / 合并）—— GEDCOM
    val gedcomData = pendingGedcom
    if (gedcomData != null) {
        AlertDialog(
            onDismissRequest = { pendingGedcom = null },
            title = { Text(i18n.s("import_title")) },
            text = { Text(i18n.s("import_prompt")) },
            confirmButton = {
                TextButton(onClick = {
                    val result = viewModel.importFamilyData(gedcomData, merge = false)
                    pendingGedcom = null
                    toast(if (result is ImportResult.Success) i18n.s("import_replace_ok") else (result as ImportResult.Error).message)
                }) { Text(i18n.s("replace_data")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val result = viewModel.importFamilyData(gedcomData, merge = true)
                    pendingGedcom = null
                    toast(if (result is ImportResult.Success) i18n.s("import_merge_ok") else (result as ImportResult.Error).message)
                }) { Text(i18n.s("merge_import")) }
            },
        )
    }

    // 清空确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(i18n.s("clear_title")) },
            text = { Text(i18n.s("clear_text")) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAll()
                    toast(i18n.s("cleared"))
                }) { Text(i18n.s("clear_btn"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(i18n.s("cancel")) }
            },
        )
    }
}

@Composable
private fun TransferButton(
    icon: ImageVector,
    title: String,
    desc: String,
    i18n: com.example.familytree.data.I18n,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = if (danger) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
