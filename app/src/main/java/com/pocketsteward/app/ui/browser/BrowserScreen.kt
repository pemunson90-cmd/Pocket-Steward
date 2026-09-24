package com.pocketsteward.app.ui.browser

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import com.pocketsteward.app.browser.BrowserItem
import com.pocketsteward.app.browser.BrowserSort
import com.pocketsteward.app.browser.ClipMode
import com.pocketsteward.app.browser.SortKey
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.ui.components.FileKind
import com.pocketsteward.app.ui.components.FileVisual
import com.pocketsteward.app.ui.scan.formatBytes
import com.pocketsteward.app.ui.scan.openDirectFile
import com.pocketsteward.app.ui.scan.operationSummary
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Set when another app asked Pocket Steward to pick a file. */
data class PickRequest(val mimeTypes: List<String>, val multiple: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    pick: PickRequest? = null,
    onPicked: (List<BrowserItem>) -> Unit = {},
    onCancelPick: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val vm: BrowserViewModel = viewModel(factory = viewModelFactory { initializer { BrowserViewModel(container) } })
    val s by vm.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<BrowserItem?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var detailsTarget by remember { mutableStateOf<BrowserItem?>(null) }
    var pickPicked by remember { mutableStateOf<Map<String, BrowserItem>>(emptyMap()) }
    val pickSelection = pickPicked.keys

    BackHandler(enabled = s.inSelection || s.location != BrowserLocation.Home || searching) {
        if (searching) searching = false else vm.back()
    }

    LaunchedEffect(s.outcome) {
        val outcome = s.outcome ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = outcome.message,
            actionLabel = if (outcome.canUndo) "Undo" else null,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undo(outcome) else vm.dismissOutcome()
    }
    LaunchedEffect(s.message) {
        val msg = s.message?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (s.items.isEmpty() && s.contentHits.isEmpty() && s.location !is BrowserLocation.Home) return@LaunchedEffect
        snackbar.showSnackbar(msg, withDismissAction = true)
        vm.dismissMessage()
    }

    val visibleItems = if (pick == null) s.items else s.items.filter { it.isDirectory || matchesPick(it, pick.mimeTypes) }
    val visibleHits = if (pick == null) s.contentHits else s.contentHits.filter { matchesPick(it.item, pick.mimeTypes) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                s.inSelection && pick == null -> SelectionTopBar(
                    count = s.selection.size,
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                )
                searching -> SearchTopBar(
                    query = query,
                    onQuery = { query = it },
                    onSubmit = {
                        vm.search(query)
                        searching = false
                    },
                    onClose = { searching = false },
                )
                else -> MainTopBar(
                    s = s,
                    pick = pick,
                    onBack = { if (!vm.back() && pick != null) onCancelPick() },
                    onSearch = { searching = true },
                    onSort = vm::setSort,
                    onToggleHidden = vm::toggleHidden,
                    onToggleGrid = vm::toggleGrid,
                    onProtect = vm::protectCurrentFolder,
                    onCancelPick = onCancelPick,
                )
            }
        },
        bottomBar = {
            when {
                pick != null && pick.multiple && pickPicked.isNotEmpty() -> ActionBar {
                    Button(onClick = { onPicked(pickPicked.values.toList()) }) {
                        Text("Select ${pickPicked.size}")
                    }
                }
                pick != null -> Unit
                s.inSelection -> SelectionActions(
                    s = s,
                    onShare = { shareItems(context, s.selectedItems) },
                    onCut = vm::cut,
                    onCopy = vm::copy,
                    onRename = { renameTarget = s.selectedItems.singleOrNull() },
                    onTrash = vm::trashSelected,
                    onDetails = { detailsTarget = s.selectedItems.singleOrNull() },
                )
                s.clipboard != null -> ClipboardBar(
                    clipboard = s.clipboard!!,
                    canPaste = s.currentFolder != null,
                    onPaste = vm::paste,
                    onCancel = vm::clearClipboard,
                )
            }
        },
        floatingActionButton = {
            if (pick == null && s.currentFolder != null && !s.inSelection && s.clipboard == null) {
                FloatingActionButton(onClick = { newFolderOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New folder")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            s.working?.let {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                !s.ready -> Box(Modifier.fillMaxSize())
                s.noAccess -> Centered("Pocket Steward needs storage access to show your files. Grant it in Settings.")
                s.location == BrowserLocation.Home -> BrowserHome(
                    s = s,
                    onSearch = { searching = true },
                    onCategory = vm::openCategory,
                    onRoot = vm::openRoot,
                    onFolder = { ref, name -> vm.openFolder(ref, name) },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    if (s.location is BrowserLocation.Folder) Crumbs(s.crumbs, vm::openCrumb)
                    PullToRefreshBox(
                        isRefreshing = s.loading && s.items.isNotEmpty(),
                        onRefresh = vm::reload,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ItemsPane(
                            s = s.copy(contentHits = visibleHits),
                            items = visibleItems,
                            pickSelection = pickSelection,
                            onOpen = { item ->
                                when {
                                    s.inSelection && pick == null -> vm.toggleSelected(item)
                                    item.isDirectory -> vm.openFolder(item.ref, item.name)
                                    pick != null && pick.multiple -> pickPicked =
                                        if (item.key in pickPicked) pickPicked - item.key else pickPicked + (item.key to item)
                                    pick != null -> onPicked(listOf(item))
                                    else -> openItem(context, item)
                                }
                            },
                            onLongPress = { item ->
                                if (pick == null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelected(item)
                                }
                            },
                            onShowInFolder = vm::showInFolder,
                        )
                    }
                }
            }
        }
    }

    s.pending?.let { pending ->
        ConfirmSheet(
            pending = pending,
            onRun = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.confirmPending()
            },
            onCancel = vm::dismissPending,
        )
    }
    renameTarget?.let { item ->
        NameDialog(
            title = "Rename",
            initial = item.name,
            confirm = "Rename",
            onConfirm = { name ->
                renameTarget = null
                vm.rename(item, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (newFolderOpen) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            onConfirm = { name ->
                newFolderOpen = false
                vm.newFolder(name)
            },
            onDismiss = { newFolderOpen = false },
        )
    }
    detailsTarget?.let { item -> DetailsDialog(item) { detailsTarget = null } }
}

// ---- Top bars ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    s: BrowserUiState,
    pick: PickRequest?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSort: (BrowserSort) -> Unit,
    onToggleHidden: () -> Unit,
    onToggleGrid: () -> Unit,
    onProtect: () -> Unit,
    onCancelPick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val title = when (val loc = s.location) {
        BrowserLocation.Home -> if (pick != null) "Choose a file" else "Files"
        is BrowserLocation.Folder -> s.crumbs.lastOrNull()?.name ?: "Folder"
        is BrowserLocation.Category -> loc.category.label
        is BrowserLocation.Search -> "“${loc.query}”"
    }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (s.location != BrowserLocation.Home) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            } else if (pick != null) {
                IconButton(onClick = onCancelPick) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
            }
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search files") }
            if (s.location != BrowserLocation.Home) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SortKey.entries.forEach { key ->
                        val active = s.sort.key == key
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Sort by ${key.label}" + when {
                                        !active -> ""
                                        s.sort.descending -> "  ↓"
                                        else -> "  ↑"
                                    },
                                )
                            },
                            onClick = {
                                menu = false
                                onSort(if (active) s.sort.copy(descending = !s.sort.descending) else BrowserSort(key, key == SortKey.MODIFIED || key == SortKey.SIZE))
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (s.grid) "Show as list" else "Show as grid") }, onClick = { menu = false; onToggleGrid() })
                    DropdownMenuItem(text = { Text(if (s.showHidden) "Hide hidden files" else "Show hidden files") }, onClick = { menu = false; onToggleHidden() })
                    if (pick == null && s.currentFolder != null) {
                        DropdownMenuItem(text = { Text("Protect this folder") }, onClick = { menu = false; onProtect() })
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Clear selection") } },
        actions = { TextButton(onClick = onSelectAll) { Text("Select all") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit, onClose: () -> Unit) {
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("File names or words inside files") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit() }),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") } },
    )
}

// ---- Home -------------------------------------------------------------------

@Composable
private fun BrowserHome(
    s: BrowserUiState,
    onSearch: () -> Unit,
    onCategory: (BrowserCategory) -> Unit,
    onRoot: () -> Unit,
    onFolder: (FileRef, String) -> Unit,
) {
    val home = s.home
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                onClick = onSearch,
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text(
                        "Search names and contents",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        item {
            Card(onClick = onRoot, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_notification), contentDescription = null, modifier = Modifier.size(28.dp))
                        Text(
                            if (s.mode == com.pocketsteward.app.storage.StorageAccessMode.SAF) "Granted folder" else "Internal storage",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    val total = home?.totalBytes
                    val free = home?.freeBytes
                    if (total != null && free != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { ((total - free).toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        Text(
                            "${formatBytes(total - free)} used of ${formatBytes(total)} · ${formatBytes(free)} free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        val shortcuts = home?.shortcuts.orEmpty()
        if (shortcuts.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shortcuts) { (ref, label) ->
                        OutlinedButton(onClick = { onFolder(ref, (ref as FileRef.Direct).absolutePath.substringAfterLast('/')) }) { Text(label) }
                    }
                }
            }
        }
        item { Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        val stats = home?.stats.orEmpty()
        val tiles = BrowserCategory.entries
        tiles.chunked(2).forEach { pair ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { cat ->
                        val stat = stats.firstOrNull { it.category == cat }
                        Card(onClick = { onCategory(cat) }, modifier = Modifier.weight(1f)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                FileVisual(
                                    name = categorySample(cat),
                                    location = null,
                                    isDirectory = cat == BrowserCategory.RECENT,
                                    size = 36.dp,
                                )
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(cat.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when {
                                            cat == BrowserCategory.RECENT -> "Newest first"
                                            stat == null -> "…"
                                            else -> "${"%,d".format(stat.files)} · ${formatBytes(stat.bytes)}"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        if (home != null && !home.libraryReady) {
            item {
                Text(
                    "Your file library is still being built in the background. Categories and search fill in as it finishes; folders are always live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun categorySample(cat: BrowserCategory): String = when (cat) {
    BrowserCategory.IMAGES -> "a.jpg"
    BrowserCategory.VIDEOS -> "a.mp4"
    BrowserCategory.AUDIO -> "a.mp3"
    BrowserCategory.DOCUMENTS -> "a.pdf"
    BrowserCategory.APPS -> "a.apk"
    BrowserCategory.ARCHIVES -> "a.zip"
    BrowserCategory.RECENT -> "Recent"
}

// ---- Listing ----------------------------------------------------------------

@Composable
private fun Crumbs(crumbs: List<Crumb>, onOpen: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(crumbs) { i, crumb ->
            val last = i == crumbs.lastIndex
            TextButton(onClick = { onOpen(i) }, enabled = !last) {
                Text(
                    crumb.name,
                    maxLines = 1,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                )
            }
            if (!last) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemsPane(
    s: BrowserUiState,
    items: List<BrowserItem>,
    pickSelection: Set<String>,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
    onShowInFolder: (BrowserItem) -> Unit,
) {
    val mixed = s.location !is BrowserLocation.Folder
    if (items.isEmpty() && s.contentHits.isEmpty()) {
        if (s.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Centered(s.message?.takeIf { it.isNotBlank() } ?: "Nothing here.")
        }
        return
    }
    if (s.grid && !mixed) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.key }) { item ->
                GridCell(item, selected = item.key in s.selection || item.key in pickSelection, onOpen, onLongPress, Modifier.animateItem())
            }
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (s.location is BrowserLocation.Search && items.isNotEmpty()) {
            item(key = "h-names") { SectionLabel("Names") }
        }
        items(items, key = { it.key }) { item ->
            ItemRow(
                item = item,
                selectionMode = s.inSelection || pickSelection.isNotEmpty(),
                selected = item.key in s.selection || item.key in pickSelection,
                showLocation = mixed,
                onOpen = onOpen,
                onLongPress = onLongPress,
                onShowInFolder = onShowInFolder,
                modifier = Modifier.animateItem(),
            )
        }
        if (s.contentHits.isNotEmpty()) {
            item(key = "h-inside") { SectionLabel("Inside files") }
            items(s.contentHits, key = { "c:" + it.item.key }) { hit ->
                ContentHitRow(hit, onOpen = { onOpen(hit.item) }, onShowInFolder = { onShowInFolder(hit.item) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: BrowserItem,
    selectionMode: Boolean,
    selected: Boolean,
    showLocation: Boolean,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
    onShowInFolder: (BrowserItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val detail = itemDetail(item, showLocation)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = { onOpen(item) },
                onLongClick = { onLongPress(item) },
                onLongClickLabel = "Select",
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = (if (item.isDirectory) "Folder " else "") + item.name + ", " + detail
                if (selectionMode) stateDescription = if (selected) "Selected" else "Not selected"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FileVisual(name = item.name, location = item.ref.rawValue(), mimeType = item.mimeType, isDirectory = item.isDirectory)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onOpen(item) })
        } else if (showLocation) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options for ${item.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Show in folder") }, onClick = { menu = false; onShowInFolder(item) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    item: BrowserItem,
    selected: Boolean,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(4.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .combinedClickable(onClick = { onOpen(item) }, onLongClick = { onLongPress(item) })
            .padding(6.dp)
            .semantics(mergeDescendants = true) { contentDescription = (if (item.isDirectory) "Folder " else "") + item.name },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileVisual(name = item.name, location = item.ref.rawValue(), mimeType = item.mimeType, isDirectory = item.isDirectory, size = 88.dp)
        Text(
            item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).width(96.dp),
        )
    }
}

@Composable
private fun ContentHitRow(hit: ContentHit, onOpen: () -> Unit, onShowInFolder: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FileVisual(name = hit.item.name, location = hit.item.ref.rawValue())
        Column(Modifier.weight(1f)) {
            Text(hit.item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (hit.page?.let { "Page $it · " } ?: "") + hit.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onShowInFolder, contentPadding = PaddingValues(0.dp)) { Text("Show in folder") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

private fun itemDetail(item: BrowserItem, showLocation: Boolean): String {
    val parts = mutableListOf<String>()
    if (item.isDirectory) {
        item.childCount?.let { parts += if (it == 1) "1 item" else "$it items" } ?: parts.add("Folder")
    } else {
        parts += formatBytes(item.sizeBytes)
    }
    item.modifiedAt?.let { parts += DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    if (showLocation) item.parentRef?.let { parts += it.displayName() }
    return parts.joinToString(" · ")
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Bottom bars ------------------------------------------------------------

@Composable
private fun ActionBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun LabeledAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, contentDescription = null) else Box(Modifier.height(24.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SelectionActions(
    s: BrowserUiState,
    onShare: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit,
    onDetails: () -> Unit,
) {
    val single = s.selection.size == 1
    val anyFolder = s.selectedItems.any { it.isDirectory }
    ActionBar {
        LabeledAction("Share", Icons.Default.Share, onShare, enabled = !anyFolder)
        LabeledAction("Move", null, onCut)
        LabeledAction("Copy", null, onCopy)
        LabeledAction("Rename", Icons.Default.Edit, onRename, enabled = single)
        LabeledAction("Trash", Icons.Default.Delete, onTrash)
        LabeledAction("Details", Icons.Default.Info, onDetails, enabled = single)
    }
}

@Composable
private fun ClipboardBar(clipboard: com.pocketsteward.app.browser.Clipboard, canPaste: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    val n = clipboard.items.size
    val what = if (n == 1) clipboard.items.single().name else "$n items"
    ActionBar {
        Text(
            if (canPaste) "Go to a folder, then paste" else "Open a folder to paste into",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            maxLines = 2,
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = onPaste, enabled = canPaste) {
            Text(if (clipboard.mode == ClipMode.MOVE) "Move $what here" else "Copy $what here", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Sheets and dialogs -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(pending: PendingPlan, onRun: () -> Unit, onCancel: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = pending.accepted.size > 6)
    val trashes = pending.accepted.count { it is PlannedOperation.Trash }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(pending.goal, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    buildString {
                        append(if (pending.accepted.size == 1) "1 change" else "${pending.accepted.size} changes")
                        if (pending.rejected.isNotEmpty()) append(" · ${pending.rejected.size} not possible")
                        append(" · undoable from Tasks")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }
            items(pending.notes) { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(pending.accepted.take(300)) { op ->
                val trash = op is PlannedOperation.Trash
                Text(
                    operationSummary(op),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (pending.accepted.size > 300) {
                item { Text("…and ${pending.accepted.size - 300} more", style = MaterialTheme.typography.bodySmall) }
            }
            if (pending.rejected.isNotEmpty()) {
                item { Text("Not possible", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(pending.rejected.take(50)) { r ->
                    Text(
                        "${operationSummary(r.operation)}: ${r.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    Button(
                        onClick = onRun,
                        enabled = pending.accepted.isNotEmpty(),
                        colors = if (trashes > 0) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        Text(if (trashes > 0 && trashes == pending.accepted.size) "Move to Trash" else "Run")
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailsDialog(item: BrowserItem, onDismiss: () -> Unit) {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine("Type", if (item.isDirectory) "Folder" else FileKind.of(item.name, item.mimeType).spokenName)
                if (!item.isDirectory) DetailLine("Size", "${formatBytes(item.sizeBytes)} (${"%,d".format(item.sizeBytes)} bytes)")
                item.childCount?.let { DetailLine("Contains", "$it items") }
                item.modifiedAt?.let { DetailLine("Modified", fmt.format(Date(it))) }
                DetailLine("Location", (item.ref as? FileRef.Direct)?.absolutePath ?: item.ref.rawValue())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- Intents ----------------------------------------------------------------

private fun openItem(context: Context, item: BrowserItem) {
    openDirectFile(context, item.ref.rawValue(), item.name, com.pocketsteward.app.browser.BrowserLogic.extensionOf(item.name))
}

/** A content URI another app can read: a FileProvider URI for plain paths, the document URI for granted folders. */
internal fun contentUriFor(context: Context, item: BrowserItem): Uri? = when (val ref = item.ref) {
    is FileRef.Direct -> runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(ref.absolutePath))
    }.getOrNull()
    is FileRef.Saf -> Uri.parse(ref.documentUri)
    is FileRef.Child -> null
}

internal fun mimeOf(item: BrowserItem): String =
    item.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(com.pocketsteward.app.browser.BrowserLogic.extensionOf(item.name))
        ?: "application/octet-stream"

private fun shareItems(context: Context, items: List<BrowserItem>) {
    val files = items.filter { !it.isDirectory }
    val uris = files.mapNotNull { contentUriFor(context, it) }
    if (uris.isEmpty()) {
        Toast.makeText(context, "Nothing shareable selected.", Toast.LENGTH_SHORT).show()
        return
    }
    val types = files.map(::mimeOf).distinct()
    val type = when {
        types.size == 1 -> types.single()
        types.map { it.substringBefore('/') }.distinct().size == 1 -> types.first().substringBefore('/') + "/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        setType(type)
        clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
        .onFailure { Toast.makeText(context, "No app can receive this.", Toast.LENGTH_SHORT).show() }
}

internal fun matchesPick(item: BrowserItem, mimeTypes: List<String>): Boolean {
    if (mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" || it == "*" }) return true
    val mime = mimeOf(item)
    return mimeTypes.any { wanted ->
        if (wanted.endsWith("/*")) mime.startsWith(wanted.removeSuffix("*")) else mime.equals(wanted, ignoreCase = true)
    }
}
