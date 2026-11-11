package com.aniob.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aniob.app.R
import com.aniob.app.auth.AniobAccountSettingsUi
import com.aniob.app.auth.AniobAccountTestResult
import com.aniob.app.auth.AniobAccountViewModel
import com.aniob.core.auth.AniobAccountScreenState

/**
 * Settings card for "Sign in with an account". Draws nothing when the build has no account
 * sign-in configured. Showing it makes no network or model call.
 */
@Composable
fun AniobAccountSignInCard(modifier: Modifier = Modifier, viewModel: AniobAccountViewModel = viewModel()) {
    if (!viewModel.available) return
    val screen by viewModel.screenState.collectAsState()
    val settings by viewModel.settings.collectAsState()

    Card(modifier = modifier.fillMaxWidth().testTag("account_sign_in_card")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.account_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.account_chatgpt), style = MaterialTheme.typography.titleSmall)
            if (settings.revocationUnconfirmed) Text(stringResource(R.string.account_revoke_unconfirmed))
            when (val s = screen) {
                AniobAccountScreenState.Intro -> {
                    Intro(onContinue = viewModel::startSignIn)
                    DeleteProvider(viewModel)
                }
                AniobAccountScreenState.Waiting -> Waiting(onCancel = viewModel::cancelSignIn)
                is AniobAccountScreenState.Connected -> Connected(s, settings, viewModel)
                is AniobAccountScreenState.Problem -> {
                    Problem(s, onRetry = viewModel::startSignIn)
                    DeleteProvider(viewModel)
                }
            }
        }
    }
}

@Composable
private fun Intro(onContinue: () -> Unit) {
    Text(stringResource(R.string.account_intro), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("account_continue")) {
        Text(stringResource(R.string.account_continue))
    }
}

@Composable
private fun Waiting(onCancel: () -> Unit) {
    val description = stringResource(R.string.account_waiting_description)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp).semantics { contentDescription = description })
        Text(stringResource(R.string.account_waiting))
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("account_cancel")) {
        Text(stringResource(R.string.account_cancel))
    }
}

@Composable
private fun Problem(state: AniobAccountScreenState.Problem, onRetry: () -> Unit) {
    Text(
        stringResource(errorText(state.messageKey)),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("account_error")
    )
    if (state.workspaceHint) {
        Text(stringResource(R.string.account_workspace_hint), style = MaterialTheme.typography.bodySmall)
    }
    if (state.retry) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("account_retry")) {
            Text(stringResource(R.string.account_try_again))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Connected(
    state: AniobAccountScreenState.Connected,
    ui: AniobAccountSettingsUi,
    vm: AniobAccountViewModel
) {
    Text(stringResource(R.string.account_connected), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.account_signed_in_as, state.label))
    state.planType?.let { Text(stringResource(R.string.account_plan, it)) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.account_use_as_cloud), modifier = Modifier.weight(1f))
        Switch(
            checked = ui.useAsCloud,
            onCheckedChange = vm::setUseAsCloud,
            modifier = Modifier.testTag("account_use_as_cloud")
        )
    }

    Text(stringResource(R.string.account_model), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.models.forEach { model ->
            FilterChip(
                selected = ui.model == model.id,
                onClick = { vm.selectModel(model.id) },
                label = { Text(model.label) }
            )
        }
    }
    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            if (it.isNotBlank()) vm.selectModel(it)
        },
        label = { Text(stringResource(R.string.account_model_other)) },
        isError = ui.modelError,
        supportingText = if (ui.modelError) ({ Text(stringResource(R.string.account_model_invalid)) }) else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("account_model_input")
    )

    Text(stringResource(R.string.account_effort), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "" to R.string.account_effort_default,
            "low" to R.string.account_effort_low,
            "medium" to R.string.account_effort_medium,
            "high" to R.string.account_effort_high
        ).forEach { (value, label) ->
            FilterChip(
                selected = ui.effort == value,
                onClick = { vm.setEffort(value) },
                label = { Text(stringResource(label)) }
            )
        }
    }

    Button(onClick = vm::sendTest, enabled = !ui.testing, modifier = Modifier.fillMaxWidth().testTag("account_test")) {
        Text(stringResource(if (ui.testing) R.string.account_testing else R.string.account_test))
    }
    ui.testResult?.let {
        Text(stringResource(testText(it)), modifier = Modifier.testTag("account_test_result"))
        if (it != AniobAccountTestResult.OK) {
            ui.testErrorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp).testTag("account_test_error_message")
                )
            }
        }
    }

    OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth().testTag("account_sign_out")) {
        Text(stringResource(R.string.account_sign_out))
    }
    DeleteProvider(vm)
}

@Composable
private fun DeleteProvider(vm: AniobAccountViewModel) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().testTag("account_delete")) {
        Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.account_delete)) },
        text = { Text(stringResource(R.string.account_delete_description)) },
        confirmButton = { TextButton(onClick = { confirm = false; vm.deleteProvider() }) {
            Text(stringResource(R.string.account_delete))
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } }
    )
}

private fun testText(result: AniobAccountTestResult): Int = when (result) {
    AniobAccountTestResult.OK -> R.string.account_test_ok
    AniobAccountTestResult.AUTH -> R.string.account_test_auth
    AniobAccountTestResult.SERVICE -> R.string.account_test_service
    AniobAccountTestResult.BUSY -> R.string.account_test_busy
    AniobAccountTestResult.OFFLINE -> R.string.account_test_offline
    AniobAccountTestResult.REJECTED -> R.string.account_test_rejected
}

/** Maps a typed error's message key (see `AniobAuthError`) to its string resource. */
internal fun errorText(key: String): Int = when (key) {
    "account_error_identity" -> R.string.account_error_identity
    "account_error_client" -> R.string.account_error_client
    "account_error_refresh_config" -> R.string.account_error_refresh_config
    "account_error_cancelled" -> R.string.account_error_cancelled
    "account_error_state_mismatch" -> R.string.account_error_state_mismatch
    "account_error_timeout" -> R.string.account_error_timeout
    "account_error_port_busy" -> R.string.account_error_port_busy
    "account_error_no_browser" -> R.string.account_error_no_browser
    "account_error_no_access" -> R.string.account_error_no_access
    "account_error_not_configured" -> R.string.account_error_not_configured
    "account_error_exchange_rejected" -> R.string.account_error_exchange_rejected
    "account_error_authorization_rejected" -> R.string.account_error_authorization_rejected
    "account_error_session_ended" -> R.string.account_error_session_ended
    "account_error_workspace_denied" -> R.string.account_error_workspace_denied
    "account_error_network" -> R.string.account_error_network
    else -> R.string.account_error_unreadable
}
