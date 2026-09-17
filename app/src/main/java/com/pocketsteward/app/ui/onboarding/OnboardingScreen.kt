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
import com.pocketsteward.app.storage.StorageAccessMode

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
    val storageAccessState by container.settingsRepository.storageAccessState
        .collectAsState(initial = StorageAccessState())
    LaunchedEffect(storageAccessState) {
        if (storageAccessState.mode == StorageAccessMode.SAF) {
            onAccessGranted()
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        broadAccessGranted = Environment.isExternalStorageManager()
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
            onAccessGranted()
        }
    }

    LaunchedEffect(broadAccessGranted) {
        if (broadAccessGranted) {
            viewModel.onBroadAccessGranted()
            onAccessGranted()
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
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    manageStorageLauncher.launch(intent)
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
