@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.familytree.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.familytree.data.FamilyViewModel
import com.example.familytree.data.Gender
import com.example.familytree.data.ParseResult
import com.example.familytree.data.ParsedRel
import com.example.familytree.data.RelationType
import com.example.familytree.data.TextParser
import com.example.familytree.ui.theme.LocalI18n
import kotlinx.coroutines.launch

private const val EXAMPLE_TEXT = "张建国和李秀英是夫妻。\n" +
    "张建国和李秀英的儿子是张伟和张强。\n" +
    "张伟娶了王芳。\n" +
    "张伟和王芳的儿子是张小明。\n" +
    "张伟和王芳的女儿是张小红。\n" +
    "张小明娶了刘华。\n" +
    "张小明和刘华的儿子是张子轩。"

@Composable
fun TextImportScreen(
    viewModel: FamilyViewModel,
    onBack: () -> Unit,
) {
    val i18n = LocalI18n.current
    var text by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var familyId by rememberSaveable { mutableStateOf(viewModel.defaultFamiliesForNew().firstOrNull() ?: "") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun relLabel(rel: ParsedRel): String = when (rel.type) {
        RelationType.FATHER -> i18n.s("rel_father")
        RelationType.MOTHER -> i18n.s("rel_mother")
        RelationType.SON -> i18n.s("rel_son")
        RelationType.DAUGHTER -> i18n.s("rel_daughter")
        RelationType.SPOUSE -> i18n.s("rel_spouse")
        RelationType.SIBLING -> i18n.s("rel_sibling")
        RelationType.LIANJIN -> i18n.s("l_lianjin")
        RelationType.ZHOULI -> i18n.s("l_zhouli")
        RelationType.CUSTOM -> rel.label.ifBlank { i18n.s("l_kin") }
        RelationType.PARENT -> i18n.s("l_parent")
    }

    Scaffold(
        topBar = {
            GlassTopBar(
                title = { Text(i18n.s("text_import_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, i18n.s("back"))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                i18n.s("ti_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { text = EXAMPLE_TEXT }) { Text(i18n.s("fill_example")) }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it; result = null },
                label = { Text(i18n.s("desc_label")) },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            FamilyPicker(
                label = i18n.s("import_to_family"),
                families = viewModel.data.families,
                selectedId = familyId,
            ) { familyId = it }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val r = TextParser.parse(text)
                    result = r
                    if (r.persons.isEmpty()) {
                        scope.launch { snackbar.showSnackbar(i18n.s("unrecognized")) }
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(i18n.s("recognize")) }

            val r = result
            if (r != null && r.persons.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                SectionTitle(i18n.s("result_title"))
                Text(
                    i18n.s("result_stats", r.persons.size, r.relations.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(10.dp))

                r.persons.forEach { p ->
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeChip(
                            when (p.gender) {
                                Gender.MALE -> i18n.s("male")
                                Gender.FEMALE -> i18n.s("female")
                                Gender.UNKNOWN -> "?"
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                    }
                }

                Spacer(Modifier.height(10.dp))
                r.relations.forEach { rel ->
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeChip(relLabel(rel))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${rel.from} → ${rel.to}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (r.unmatched.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        i18n.s("unmatched", r.unmatched.joinToString("；")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val (np, nr) = viewModel.importParsed(r.persons, r.relations, familyId)
                        scope.launch {
                            snackbar.showSnackbar(i18n.s("imported_toast", np, nr))
                        }
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(i18n.s("import_generate")) }
            }
        }
    }
}
