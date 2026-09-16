package dev.kbt117.keyproxy.domain.model

/**
 * Lifecycle of the embedded Ktor server.
 *
 * Modelled as a sealed interface rather than a boolean so the UI can render the
 * transient states (binding a socket can fail) and so [Failed] can carry the
 * reason.
 */
sealed interface ServerState {

    /** No socket is bound. */
    data object Stopped : ServerState

    /** `start()` has been called but the engine has not finished binding. */
    data object Starting : ServerState

    /**
     * Listening and serving.
     *
     * @property port The port actually bound - reported by the engine, which is
     *   the source of truth if the user requested an ephemeral port.
     */
    data class Running(val port: Int) : ServerState

    /** `stop()` has been called and the engine is draining connections. */
    data object Stopping : ServerState

    /**
     * Startup or runtime failure.
     *
     * @property message Safe-to-display cause, e.g. "Address already in use".
     */
    data class Failed(val message: String) : ServerState
}

/** Convenience for `when` branches that only care about "is it up?". */
val ServerState.isRunning: Boolean get() = this is ServerState.Running

/** Bound port when running, otherwise `null`. */
val ServerState.portOrNull: Int? get() = (this as? ServerState.Running)?.port
