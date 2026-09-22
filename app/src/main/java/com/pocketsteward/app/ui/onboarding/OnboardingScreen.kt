package com.pocketsteward.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pocketsteward.app.data.settings.StorageAccessState

@Composable
fun OnboardingScreen(onAccessGranted: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PocketStewardApplication).container
    val viewModel: OnboardingViewModel = viewModel(
        factory = viewModelFactory {
            initializer { OnboardingViewModel(container.settingsRepository) }
        },
    )

    var broadAccessGranted by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    // Covers cold start after a previous run: if the user already picked a SAF
    // tree, that grant survives app restarts and a reboot, so don't re-prompt.
    //
    // Navigation is driven off the persisted state, not off the button press:
    // the writes below run in viewModelScope, and navigating away pops this
    // destination, which clears the ViewModel and cancels that scope. Waiting
    // for the mode to actually land in DataStore before leaving is what stops
    // the grant from being silently dropped. Also covers cold start after a
    // previous run, where the grant already survives.
    val storageAccessState by container.settingsRepository.storageAccessState
        .collectAsState(initial = StorageAccessState())
    LaunchedEffect(storageAccessState, broadAccessGranted) {
        val safGrant = storageAccessState.safTreeUri?.let { saved ->
            context.contentResolver.persistedUriPermissions.firstOrNull {
                it.uri.toString() == saved
            }
        }
        val usable = StorageAccessGrantPolicy.isUsable(
            state = storageAccessState,
            broadAccessGranted = broadAccessGranted,
            safReadGranted = safGrant?.isReadPermission == true,
            safWriteGranted = safGrant?.isWritePermission == true,
        )
        when {
            usable -> onAccessGranted()
            storageAccessState.mode != null -> viewModel.onStorageAccessInvalid()
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        broadAccessGranted = Environment.isExternalStorageManager()
        if (broadAccessGranted) {
            viewModel.onBroadAccessGranted()
        }
    }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.onSafTreeSelected(uri.toString())
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = stringResource(R.string.onboarding_title))
            Text(text = stringResource(R.string.onboarding_body))

            Button(
                onClick = {
                    if (Environment.isExternalStorageManager()) {
                        broadAccessGranted = true
                        viewModel.onBroadAccessGranted()
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        manageStorageLauncher.launch(intent)
                    }
                },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(text = stringResource(R.string.onboarding_grant_broad_access))
            }

            OutlinedButton(
                onClick = { openTreeLauncher.launch(null) },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(text = stringResource(R.string.onboarding_choose_folder_instead))
            }
        }
    }
}
