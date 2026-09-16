package dev.kbt117.keyproxy.presentation.logs

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kbt117.keyproxy.logging.ProxyLogBuffer
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen 3 from the brief: the scrolling request log.
 *
 * Thin on purpose - the buffer is already a `StateFlow`, so the ViewModel only
 * exists to give Hilt a place to inject it and to keep the screen free of
 * infrastructure types.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logBuffer: ProxyLogBuffer,
) : ViewModel() {

    val entries: StateFlow<List<dev.kbt117.keyproxy.domain.model.ProxyLogEntry>> = logBuffer.entries

    val stats: StateFlow<ProxyLogBuffer.Stats> = logBuffer.stats

    fun clear() = logBuffer.clear()
}
