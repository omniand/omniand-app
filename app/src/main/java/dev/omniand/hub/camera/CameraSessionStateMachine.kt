package dev.omniand.hub.camera

/** Pure single-viewer lifecycle used by the Android camera session coordinator. */
class CameraSessionStateMachine(private val now: () -> Long = System::currentTimeMillis) {
    sealed interface State {
        data object Idle : State

        data class PendingApproval(
            val requestId: String,
            val viewerId: String,
            val publicLinkId: String,
            val viewerName: String,
            val expiresAt: Long,
            val incumbent: Streaming? = null,
        ) : State

        data class Streaming(
            val viewerId: String,
            val publicLinkId: String,
            val viewerName: String,
        ) : State
    }

    var state: State = State.Idle
        private set

    fun begin(
        requestId: String,
        viewerId: String,
        publicLinkId: String,
        viewerName: String,
        lifetimeMillis: Long,
        allowLocalTakeover: Boolean = false,
    ): State.PendingApproval? {
        expire()
        val incumbent =
            (state as? State.Streaming)?.takeIf {
                allowLocalTakeover && it.publicLinkId.isEmpty()
            }
        if ((state != State.Idle && incumbent == null) || lifetimeMillis <= 0) return null
        return State.PendingApproval(
                requestId,
                viewerId,
                publicLinkId,
                viewerName,
                now() + lifetimeMillis,
                incumbent,
            )
            .also { state = it }
    }

    fun decide(requestId: String, approved: Boolean): State? {
        expire()
        val pending = state as? State.PendingApproval ?: return null
        if (pending.requestId != requestId) return null
        state =
            if (approved)
                State.Streaming(pending.viewerId, pending.publicLinkId, pending.viewerName)
            else pending.incumbent ?: State.Idle
        return state
    }

    fun renamePending(viewerId: String, viewerName: String): Boolean {
        val pending = state as? State.PendingApproval ?: return false
        if (pending.viewerId != viewerId) return false
        state = pending.copy(viewerName = viewerName)
        return true
    }

    fun disconnect(viewerId: String): Boolean {
        val activeViewer =
            when (val current = state) {
                State.Idle -> null
                is State.PendingApproval ->
                    if (viewerId == current.viewerId) current.viewerId
                    else current.incumbent?.viewerId
                is State.Streaming -> current.viewerId
            }
        if (activeViewer != viewerId) return false
        state =
            when (val current = state) {
                is State.PendingApproval ->
                    if (viewerId == current.viewerId) current.incumbent ?: State.Idle
                    else current.copy(incumbent = null)
                else -> State.Idle
            }
        return true
    }

    fun stop(): Boolean {
        if (state == State.Idle) return false
        state = State.Idle
        return true
    }

    /** Stops a phone-local stream without interrupting an independently approved remote stream. */
    fun stopLocal(): Boolean {
        val hasLocalStream =
            when (val current = state) {
                State.Idle -> false
                is State.Streaming -> current.publicLinkId.isEmpty()
                is State.PendingApproval -> current.incumbent?.publicLinkId?.isEmpty() == true
            }
        if (!hasLocalStream) return false
        state = State.Idle
        return true
    }

    fun expire(): State.PendingApproval? {
        val pending = state as? State.PendingApproval ?: return null
        if (pending.expiresAt > now()) return null
        state = pending.incumbent ?: State.Idle
        return pending
    }
}
