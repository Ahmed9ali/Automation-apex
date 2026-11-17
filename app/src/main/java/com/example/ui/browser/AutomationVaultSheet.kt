package com.example.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.automation.AutomationEngine
import com.example.automation.AutomationVaultCategory
import com.example.automation.AutomationVaultManager
import com.example.automation.RecordingVaultCandidate
import com.example.automation.VaultFileImportCandidate
import com.example.data.local.AutomationVaultEntryEntity
import com.example.ui.theme.EmphasizedTypography
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton

internal fun vaultCategoryIcon(category: AutomationVaultCategory): ImageVector = when (category) {
    AutomationVaultCategory.PASSWORD -> Icons.Filled.Key
    AutomationVaultCategory.EMAIL -> Icons.Filled.Email
    AutomationVaultCategory.PHONE -> Icons.Filled.Phone
    AutomationVaultCategory.USERNAME -> Icons.Filled.Person
    AutomationVaultCategory.NAME -> Icons.Filled.Badge
    AutomationVaultCategory.ADDRESS -> Icons.Filled.Place
    AutomationVaultCategory.CUSTOM -> Icons.Filled.Tune
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationVaultScreenOverlay(
    entries: List<AutomationVaultEntryEntity>,
    revealedValues: Map<String, String>,
    activeTabUrl: String,
    onSaveEntry: (
        entryId: String?,
        category: AutomationVaultCategory,
        label: String,
        value: String,
        websiteOrigin: String,
        isSensitive: Boolean
    ) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRevealEntry: (String) -> Unit,
    onHideEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    backHandlerEnabled: Boolean = true,
    onImportFileCandidates: (List<VaultFileImportCandidate>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = backHandlerEnabled, onBack = onDismiss)

    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<AutomationVaultCategory?>(null) }
    var editingEntry by remember { mutableStateOf<AutomationVaultEntryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }

    val activeOrigin = remember(activeTabUrl) {
        if (activeTabUrl.startsWith("http", ignoreCase = true)) {
            AutomationEngine().parseOrigin(activeTabUrl).canonicalOrigin
        } else {
            ""
        }
    }

    if (showAddDialog) {
        AddEditAutomationVaultDialog(
            initialEntry = null,
            initialRevealedValue = "",
            defaultOriginUrl = activeOrigin,
            onSave = { cat, label, value, origin, sensitive ->
                onSaveEntry(null, cat, label, value, origin, sensitive)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    if (showUploadDialog) {
        UploadVaultFileDialog(
            existingEntries = entries,
            defaultOriginUrl = activeOrigin,
            onImportCandidates = { selectedCandidates ->
                onImportFileCandidates(selectedCandidates)
                showUploadDialog = false
            },
            onDismiss = { showUploadDialog = false }
        )
    }

    editingEntry?.let { entry ->
        val existingPlain = revealedValues[entry.entryId] ?: if (!entry.isSensitive) entry.maskedPreview else ""
        AddEditAutomationVaultDialog(
            initialEntry = entry,
            initialRevealedValue = existingPlain,
            defaultOriginUrl = entry.websiteOrigin.ifBlank { activeOrigin },
            onSave = { cat, label, value, origin, sensitive ->
                onSaveEntry(entry.entryId, cat, label, value, origin, sensitive)
                editingEntry = null
            },
            onDismiss = { editingEntry = null }
        )
    }

    val normalizedQuery = searchQuery.trim().lowercase()
    val filteredEntries = remember(entries, selectedCategory, normalizedQuery) {
        entries.filter { entry ->
            val catOk = selectedCategory == null ||
                entry.category.equals(selectedCategory?.id, ignoreCase = true)
            val queryOk = normalizedQuery.isEmpty() ||
                entry.label.lowercase().contains(normalizedQuery) ||
                entry.domain.lowercase().contains(normalizedQuery) ||
                entry.category.lowercase().contains(normalizedQuery) ||
                (!entry.isSensitive && entry.maskedPreview.lowercase().contains(normalizedQuery))
            catOk && queryOk
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("automation_vault_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.automation_vault_title),
                        style = EmphasizedTypography.headlineHero,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showUploadDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("automation_vault_upload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.UploadFile,
                            contentDescription = stringResource(R.string.automation_vault_upload)
                        )
                    }
                    IconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("automation_vault_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.automation_vault_add)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("automation_vault_fab")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.automation_vault_add)
                )
            }
        },
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("automation_vault_screen")
    ) { innerPadding ->
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .testTag("automation_vault_list")
            ) {
                item(key = "vault_intro_and_filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Encrypted with AES-256. Values from recordings or uploaded files are injected on every replay.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = { showUploadDialog = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("automation_vault_quick_upload_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.UploadFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.automation_vault_upload),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.automation_vault_search_hint),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.automation_clear))
                                    }
                                }
                            },
                            singleLine = true,
                            maxLines = 1,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("automation_vault_search_input")
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("All (${entries.size})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("vault_filter_all")
                            )
                            AutomationVaultCategory.entries.forEach { cat ->
                                val count = entries.count { it.category.equals(cat.id, ignoreCase = true) }
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = vaultCategoryIcon(cat),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("${cat.label} ($count)") },
                                    modifier = Modifier.testTag("vault_filter_${cat.id.lowercase()}")
                                )
                            }
                        }
                    }
                }

                if (filteredEntries.isEmpty()) {
                    item(key = "vault_empty") {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("automation_vault_empty_state")
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = stringResource(R.string.automation_vault_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = stringResource(R.string.automation_vault_empty_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { showUploadDialog = true },
                                        modifier = Modifier.testTag("automation_vault_empty_upload_button")
                                    ) {
                                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.automation_vault_upload))
                                    }
                                    Button(
                                        onClick = { showAddDialog = true },
                                        modifier = Modifier.testTag("automation_vault_empty_add_button")
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.automation_vault_add))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = filteredEntries,
                        key = { _, item -> item.entryId }
                    ) { _, entry ->
                        val cat = AutomationVaultCategory.fromId(entry.category)
                        val revealedPlain = revealedValues[entry.entryId]
                        val isRevealed = revealedPlain != null
                        val shownValue = when {
                            isRevealed -> revealedPlain
                            entry.isSensitive -> "••••••••"
                            else -> entry.maskedPreview
                        }
                        ElevatedCard(
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("automation_vault_entry_${entry.entryId}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = vaultCategoryIcon(cat),
                                            contentDescription = cat.label,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = cat.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = entry.domain.ifBlank { "Global" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = entry.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = shownValue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("automation_vault_value_${entry.entryId}")
                                    )
                                }
                                if (entry.isSensitive) {
                                    IconButton(
                                        onClick = {
                                            if (isRevealed) onHideEntry(entry.entryId) else onRevealEntry(entry.entryId)
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("automation_vault_reveal_${entry.entryId}")
                                    ) {
                                        Icon(
                                            imageVector = if (isRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = if (isRevealed) "Hide value" else "Reveal value"
                                        )
                                    }
                                }
                                if (!entry.isSensitive || isRevealed) {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText(entry.label, shownValue))
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("automation_vault_copy_${entry.entryId}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "Copy value",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { editingEntry = entry },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("automation_vault_edit_${entry.entryId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.automation_edit),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteEntry(entry.entryId) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("automation_vault_delete_${entry.entryId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.automation_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UploadVaultFileDialog(
    existingEntries: List<AutomationVaultEntryEntity>,
    defaultOriginUrl: String,
    onImportCandidates: (List<VaultFileImportCandidate>) -> Unit,
    onDismiss: () -> Unit,
    initialRawContent: String = ""
) {
    val context = LocalContext.current
    var rawFileText by rememberSaveable { mutableStateOf(initialRawContent) }
    var selectedFileName by rememberSaveable { mutableStateOf("") }
    var fileReadError by remember { mutableStateOf<String?>(null) }
    var candidates by remember(initialRawContent) {
        mutableStateOf(
            AutomationVaultManager.parseImportContent(
                rawContent = initialRawContent,
                existingEntries = existingEntries,
                defaultOriginUrl = defaultOriginUrl
            )
        )
    }
    var revealedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun applyRawContent(content: String) {
        rawFileText = content
        fileReadError = null
        candidates = AutomationVaultManager.parseImportContent(
            rawContent = content,
            existingEntries = existingEntries,
            defaultOriginUrl = defaultOriginUrl
        )
        revealedIds = emptySet()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val resolvedName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else ""
                }.orEmpty()
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read selected file.")
                resolvedName to text
            }.onSuccess { (name, text) ->
                selectedFileName = name.ifBlank { uri.lastPathSegment.orEmpty() }
                applyRawContent(text)
            }.onFailure { err ->
                fileReadError = err.message ?: "Could not open the selected file."
            }
        }
    }

    val selectedCount = candidates.count { it.isSelected }
    val allSelected = candidates.isNotEmpty() && selectedCount == candidates.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload File to Automation Vault") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Upload a .csv, .json, or .txt file with passwords, emails, phones, or form values, then pick which entries to save & use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/*",
                                "text/csv",
                                "text/comma-separated-values",
                                "application/json",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_upload_choose_file_button")
                ) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedFileName.isNotBlank()) "File: $selectedFileName" else "Choose File (.csv, .json, .txt)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = rawFileText,
                    onValueChange = { applyRawContent(it) },
                    label = { Text("Or paste file content") },
                    placeholder = { Text("email: user@mail.com\npassword: Secret!123") },
                    minLines = 1,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_upload_raw_input")
                )
                if (fileReadError != null) {
                    Text(
                        text = fileReadError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (candidates.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$selectedCount of ${candidates.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("vault_upload_selection_summary")
                        )
                        TextButton(
                            onClick = {
                                val nextSelected = !allSelected
                                candidates = candidates.map { it.copy(isSelected = nextSelected) }
                            },
                            modifier = Modifier.testTag("vault_upload_toggle_all_button")
                        ) {
                            Text(if (allSelected) "Deselect All" else "Select All")
                        }
                    }
                    candidates.forEachIndexed { index, item ->
                        val isRevealed = item.id in revealedIds
                        val displayValue = if (item.isSensitive && !isRevealed) "••••••••" else item.value
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (item.isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                1.dp,
                                if (item.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    candidates = candidates.map {
                                        if (it.id == item.id) it.copy(isSelected = !it.isSelected) else it
                                    }
                                }
                                .testTag("vault_upload_item_$index")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Checkbox(
                                    checked = item.isSelected,
                                    onCheckedChange = { checked ->
                                        candidates = candidates.map {
                                            if (it.id == item.id) it.copy(isSelected = checked) else it
                                        }
                                    },
                                    modifier = Modifier.testTag("vault_upload_item_checkbox_$index")
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    val allCats = AutomationVaultCategory.entries
                                                    val nextCat = allCats[(allCats.indexOf(item.category) + 1) % allCats.size]
                                                    candidates = candidates.map {
                                                        if (it.id == item.id) {
                                                            it.copy(
                                                                category = nextCat,
                                                                isSensitive = nextCat.isSensitiveDefault
                                                            )
                                                        } else {
                                                            it
                                                        }
                                                    }
                                                }
                                                .testTag("vault_upload_item_cat_$index")
                                        ) {
                                            Text(
                                                text = item.category.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (item.websiteOrigin.isNotBlank()) {
                                            Text(
                                                text = item.websiteOrigin,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = displayValue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (item.isSensitive) {
                                    IconButton(
                                        onClick = {
                                            revealedIds = if (isRevealed) revealedIds - item.id else revealedIds + item.id
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("vault_upload_item_reveal_$index")
                                    ) {
                                        Icon(
                                            imageVector = if (isRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = if (isRevealed) "Hide value" else "Reveal value",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (rawFileText.isNotBlank()) {
                    Text(
                        text = "No recognizable values detected in file. Try CSV, JSON, or key: value lines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImportCandidates(candidates) },
                enabled = selectedCount > 0,
                modifier = Modifier.testTag("vault_upload_confirm_button")
            ) {
                Text("Use Selected ($selectedCount)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditAutomationVaultDialog(
    initialEntry: AutomationVaultEntryEntity?,
    initialRevealedValue: String,
    defaultOriginUrl: String,
    onSave: (
        category: AutomationVaultCategory,
        label: String,
        value: String,
        websiteOrigin: String,
        isSensitive: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val initialCat = remember(initialEntry?.entryId) {
        AutomationVaultCategory.fromId(initialEntry?.category)
    }
    var category by remember(initialEntry?.entryId) { mutableStateOf(initialCat) }
    var label by remember(initialEntry?.entryId) { mutableStateOf(initialEntry?.label.orEmpty()) }
    var value by remember(initialEntry?.entryId, initialRevealedValue) { mutableStateOf(initialRevealedValue) }
    var restrictToOrigin by remember(initialEntry?.entryId) {
        mutableStateOf(!initialEntry?.websiteOrigin.isNullOrBlank())
    }
    var showValuePlain by remember(initialEntry?.entryId) {
        mutableStateOf(!initialCat.isSensitiveDefault)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEntry == null) "Add Vault Value" else "Edit Vault Value") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Value Type",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AutomationVaultCategory.entries.forEach { cat ->
                        val selected = category == cat
                        FilterChip(
                            selected = selected,
                            onClick = {
                                category = cat
                                if (cat == AutomationVaultCategory.PASSWORD) showValuePlain = false
                            },
                            label = { Text(cat.label) },
                            modifier = Modifier.testTag("vault_dialog_cat_${cat.id.lowercase()}")
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (e.g. Work Email, Main Phone)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_entry_label_input")
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    placeholder = {
                        Text(
                            if (initialEntry?.isSensitive == true && value.isEmpty()) {
                                "Enter new value to update"
                            } else {
                                "Enter ${category.label.lowercase()} value"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (category.isSensitiveDefault && !showValuePlain) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = {
                        if (category.isSensitiveDefault) {
                            IconButton(onClick = { showValuePlain = !showValuePlain }) {
                                Icon(
                                    imageVector = if (showValuePlain) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_entry_value_input")
                )
                if (defaultOriginUrl.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Scope to website only",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (restrictToOrigin) defaultOriginUrl else "Usable on any website",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = restrictToOrigin,
                            onCheckedChange = { restrictToOrigin = it },
                            modifier = Modifier.testTag("vault_entry_scope_switch")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveLabel = label.trim().ifBlank { category.label }
                    val effectiveOrigin = if (restrictToOrigin) defaultOriginUrl else ""
                    onSave(category, effectiveLabel, value, effectiveOrigin, category.isSensitiveDefault)
                },
                enabled = value.isNotEmpty(),
                modifier = Modifier.testTag("vault_entry_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingVaultCandidateEditDialog(
    candidate: RecordingVaultCandidate,
    existingVaultEntries: List<AutomationVaultEntryEntity>,
    onSaveCandidate: (
        category: AutomationVaultCategory,
        label: String,
        value: String,
        existingEntryId: String?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember(candidate.stepId) { mutableStateOf(candidate.category) }
    var label by remember(candidate.stepId) { mutableStateOf(candidate.vaultLabel) }
    var value by remember(candidate.stepId) { mutableStateOf(candidate.capturedValue) }
    var selectedExistingId by remember(candidate.stepId) { mutableStateOf(candidate.existingEntryId) }
    var showValuePlain by remember(candidate.stepId) { mutableStateOf(!candidate.isSensitive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grab Value for Automation Vault") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Step ${candidate.stepOrder}: ${candidate.targetLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AutomationVaultCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = {
                                category = cat
                                if (cat == AutomationVaultCategory.PASSWORD) showValuePlain = false
                            },
                            label = { Text(cat.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Vault Label") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("candidate_edit_label_input")
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        selectedExistingId = null
                    },
                    label = {
                        Text(
                            if (selectedExistingId != null) {
                                "Override value (optional — linked to Vault)"
                            } else {
                                "Value to save & replay"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (category.isSensitiveDefault && !showValuePlain) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = {
                        if (category.isSensitiveDefault) {
                            IconButton(onClick = { showValuePlain = !showValuePlain }) {
                                Icon(
                                    imageVector = if (showValuePlain) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("candidate_edit_value_input")
                )
                if (existingVaultEntries.isNotEmpty()) {
                    Text(
                        text = "Or link existing Vault entry:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    existingVaultEntries.take(6).forEach { entry ->
                        val isChosen = selectedExistingId == entry.entryId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isChosen) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            border = if (isChosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedExistingId = if (isChosen) null else entry.entryId
                                    if (!isChosen) {
                                        label = entry.label
                                        category = AutomationVaultCategory.fromId(entry.category)
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Icon(
                                    imageVector = vaultCategoryIcon(AutomationVaultCategory.fromId(entry.category)),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${entry.label} (${if (entry.isSensitive) "••••" else entry.maskedPreview})",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isChosen) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveCandidate(category, label.trim().ifBlank { category.label }, value, selectedExistingId)
                },
                enabled = value.isNotEmpty() || selectedExistingId != null,
                modifier = Modifier.testTag("candidate_edit_save_button")
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EditStepAutomationVaultSection(
    vaultEntries: List<AutomationVaultEntryEntity>,
    selectedVaultEntryId: String?,
    onSelectVaultEntry: (AutomationVaultEntryEntity?) -> Unit,
    onOpenAutomationVault: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("edit_step_automation_vault_section")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Automation Vault Value",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (selectedVaultEntryId != null) {
                    TextButton(onClick = { onSelectVaultEntry(null) }) {
                        Text("Use Custom Text")
                    }
                }
            }
            if (vaultEntries.isEmpty()) {
                Text(
                    text = "No Automation Vault values saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                vaultEntries.forEach { entry ->
                    val cat = AutomationVaultCategory.fromId(entry.category)
                    val isSelected = selectedVaultEntryId == entry.entryId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSelectVaultEntry(if (isSelected) null else entry)
                            }
                            .testTag("edit_step_vault_entry_${entry.entryId}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Icon(
                                imageVector = vaultCategoryIcon(cat),
                                contentDescription = cat.label,
                                modifier = Modifier.size(16.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${entry.label} (${cat.label})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (entry.isSensitive) "••••" else entry.maskedPreview,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (onOpenAutomationVault != null) {
                OutlinedButton(
                    onClick = onOpenAutomationVault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_step_open_automation_vault_button")
                ) {
                    Text("Manage Automation Vault")
                }
            }
        }
    }
}
