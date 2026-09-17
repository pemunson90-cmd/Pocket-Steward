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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.R
import kotlinx.coroutines.launch

private data class QuickAction(val labelRes: Int, val opensHistory: Boolean = false)

private val quickActions = listOf(
    QuickAction(R.string.home_quick_action_organize_downloads),
    QuickAction(R.string.home_quick_action_find_duplicates),
    QuickAction(R.string.home_quick_action_find_large_files),
    QuickAction(R.string.home_quick_action_find_old_files),
    QuickAction(R.string.home_quick_action_review_uncategorized),
    QuickAction(R.string.home_quick_action_recent_tasks, opensHistory = true),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onScanStorage: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.database.taskRunDao()) }
        },
    )
    val recentTasks by viewModel.recentTasks.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    val notYetImplementedMessage = stringResource(R.string.home_not_yet_implemented)

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.home_prompt_placeholder)) },
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
                items(quickActions) { action ->
                    Card(
                        onClick = {
                            if (action.opensHistory) {
                                onOpenHistory()
                            } else {
                                scope.launch { snackbarHostState.showSnackbar(notYetImplementedMessage) }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(action.labelRes),
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
