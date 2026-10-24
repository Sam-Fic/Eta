package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import android.content.Context

/** A failed catalog read/write remains visible until an explicit retry. Never show it as an empty catalog. */
internal class SubAgentPresetDirectory(private val repository: ConversationSubAgentPreferences, private val context: Context) {
    var entries by mutableStateOf<List<SubAgentPreset>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private var retryVersion by mutableIntStateOf(0)
    private fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        error = failure.message ?: failure.javaClass.simpleName
        loading = false
    }
    @Composable fun observe() {
        val version = retryVersion
        LaunchedEffect(this, version) {
            if (error != null) return@LaunchedEffect
            try {
                entries = repository.presets()
                loading = false
                repository.presetsFlow().collect { if (error == null) entries = it }
            } catch (failure: Exception) { fail(failure) }
        }
    }
    fun change(action: () -> Unit): Boolean {
        if (loading || error != null) return false
        return try {
            action()
            entries = repository.presets()
            true
        } catch (failure: Exception) { fail(failure); false }
    }
    fun retry() {
        try {
            check(repository.recoverDurability()) { context.getString(R.string.spc_restore_failed) }
            error = null
            loading = true
            retryVersion++
        } catch (failure: Exception) { fail(failure) }
    }
}

@Composable
internal fun rememberSubAgentPresetDirectory(repository: ConversationSubAgentPreferences): SubAgentPresetDirectory {
    val context = LocalContext.current
    return remember(repository) { SubAgentPresetDirectory(repository, context) }.also { it.observe() }
}

@Composable
internal fun SubAgentPresetDirectoryStatus(directory: SubAgentPresetDirectory) {
    val view = LocalView.current
    val error = directory.error
    when {
        error != null -> {
            Text(stringResource(R.string.spc_dir_error, error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { TouchHaptics.click(view); directory.retry() }) { Text(stringResource(R.string.spc_retry_read)) }
        }
        directory.loading -> Text(stringResource(R.string.spc_reading))
        directory.entries.isEmpty() -> Text(stringResource(R.string.spc_empty))
    }
}
