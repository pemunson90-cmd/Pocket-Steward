package com.pocketsteward.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import java.text.DateFormat
import java.util.Date

/**
 * Read-only review of what the app has trashed, with per-file restore.
 *
 * There is deliberately no "empty trash" action anywhere on this screen or
 * anywhere else in the app. Trashed files stay in `PocketSteward/Trash` until
 * the owner removes them by hand with a file manager. That is a standing
 * constraint, not an unfinished feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: TrashViewModel = viewModel(
        factory = viewModelFactory {
            initializer { TrashViewModel(container.database.mutationRecordDao(), container) }
        },
    )

    val trashed by viewModel.trashed.collectAsState()
    val restoreState by viewModel.restoreState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = stringResource(R.string.trash_policy))

            (restoreState as? RestoreState.Failed)?.let { failed ->
                Card(onClick = viewModel::dismissError, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        text = "Couldn't restore: ${failed.reason}. Tap to dismiss.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (trashed.isEmpty()) {
                Text(text = stringResource(R.string.trash_empty), modifier = Modifier.padding(top = 16.dp))
                return@Column
            }

            Text(text = "${trashed.size} file(s) in trash", modifier = Modifier.padding(top = 16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trashed, key = { it.mutationId }) { file ->
                    TrashedFileCard(
                        file = file,
                        restoring = (restoreState as? RestoreState.Restoring)?.mutationId == file.mutationId,
                        onRestore = { viewModel.restore(file.mutationId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashedFileCard(file: TrashedFile, restoring: Boolean, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = file.displayName)
            Text(text = "From ${file.originalPath}")
            file.keptInsteadPath?.let {
                Text(text = "Kept instead: $it")
            }
            file.trashedAt?.let {
                Text(
                    text = "Trashed ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                )
            }

            if (restoring) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            } else {
                Card(onClick = onRestore, modifier = Modifier.padding(top = 8.dp)) {
                    Text(text = "Restore", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}
