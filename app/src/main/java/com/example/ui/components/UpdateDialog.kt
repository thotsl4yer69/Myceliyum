package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.update.UpdateState
import com.example.update.UpdateViewModel

/**
 * Surfaces the [UpdateViewModel] state as Material dialogs / toasts.
 *
 * - [UpdateState.Available] → a prompt offering "Update now" (download + install)
 *   or "Later", plus a manual "View release" fallback.
 * - [UpdateState.Downloading] → a non-dismissible progress dialog.
 * - [UpdateState.UpToDate]/[UpdateState.Failed] → a toast, unless the check was
 *   silent (the automatic launch check), in which case nothing is shown.
 */
@Composable
fun UpdatePrompt(viewModel: UpdateViewModel, state: UpdateState) {
    val context = LocalContext.current

    when (state) {
        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismiss() },
                icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                title = { Text("Update available") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        val name = state.release.versionName.ifBlank { "the latest build" }
                        Text(
                            "A newer field build ($name) is ready to install.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.release.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.downloadAndInstall(state.release) },
                        modifier = Modifier.testTag("update_now_button")
                    ) { Text("Update now") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismiss() }) { Text("Later") }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* non-cancellable while downloading */ },
                icon = { CircularProgressIndicator() },
                title = { Text("Downloading update") },
                text = { Text("The installer will open automatically when the download finishes.") },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { viewModel.openReleasePage() }) {
                        Text("Open in browser")
                    }
                }
            )
        }

        is UpdateState.NeedsInstallPermission -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismiss() },
                title = { Text("Allow installs") },
                text = {
                    Text(
                        "To update in-app, allow MyceliYUMS to install apps, then tap " +
                            "\"Update now\" again."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.downloadAndInstall(state.release) }) {
                        Text("Try again")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismiss() }) { Text("Cancel") }
                }
            )
        }

        is UpdateState.UpToDate -> {
            LaunchedEffect(state) {
                if (!state.silent) {
                    Toast.makeText(context, "You're on the latest version.", Toast.LENGTH_SHORT).show()
                }
                viewModel.dismiss()
            }
        }

        is UpdateState.Failed -> {
            LaunchedEffect(state) {
                if (!state.silent) {
                    Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                }
                viewModel.dismiss()
            }
        }

        else -> Unit // Idle / Checking show no blocking UI
    }
}
