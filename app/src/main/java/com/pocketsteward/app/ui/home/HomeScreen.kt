package com.pocketsteward.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import com.pocketsteward.app.ui.scan.PostScanAction

/**
 * A Home tile. [action] is what it runs after scanning Downloads; a null
 * action means the tile opens task history instead, which needs no scan.
 *
 * Every tile here does something. Five of these six used to fall through to a
 * "not built yet" snackbar while the working versions sat two screens deep on
 * the scan summary — the app's front door advertising six features, delivering
 * one, and teaching the user it was broken.
 */
private data class QuickAction(val labelRes: Int, val action: PostScanAction?)

private val quickActions = listOf(
    QuickAction(R.string.home_quick_action_organize_downloads, PostScanAction.SMART_CLEANUP),
    QuickAction(R.string.home_quick_action_find_duplicates, PostScanAction.FIND_DUPLICATES),
    QuickAction(R.string.home_quick_action_find_large_files, PostScanAction.FIND_LARGEST),
    QuickAction(R.string.home_quick_action_find_old_files, PostScanAction.FIND_OLD),
    QuickAction(R.string.home_quick_action_review_uncategorized, PostScanAction.REVIEW_UNCATEGORIZED),
    QuickAction(R.string.home_quick_action_recent_tasks, action = null),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onScanStorage: () -> Unit,
    onOpenHistory: () -> Unit,
    onQuickAction: (PostScanAction) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.database.taskRunDao()) }
        },
    )
    val recentTasks by viewModel.recentTasks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // Disabled on purpose, not broken. Natural-language requests are
            // Milestone 6; an enabled field that silently discarded what the
            // user typed would be the same dishonesty as the dead tiles.
            OutlinedTextField(
                value = "",
                onValueChange = {},
                enabled = false,
                label = { Text(stringResource(R.string.home_prompt_placeholder)) },
                supportingText = { Text(stringResource(R.string.home_prompt_disabled)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = onScanStorage, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(text = "Scan storage")
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(quickActions) { quickAction ->
                    Card(
                        onClick = {
                            val action = quickAction.action
                            if (action == null) onOpenHistory() else onQuickAction(action)
                        },
                    ) {
                        Text(
                            text = stringResource(quickAction.labelRes),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            Text(
                text = "Recent tasks: ${recentTasks.size}",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
