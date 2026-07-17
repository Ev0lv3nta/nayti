package app.nayti.platform.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccessRevision(
    val value: Long,
    val permission: MediaPermissionSnapshot,
)

class AccessRevisionGate(
    initialPermission: MediaPermissionSnapshot,
    private val permissionReader: MediaPermissionReader,
) {
    private val mutableState = MutableStateFlow(AccessRevision(1, initialPermission))

    val state: StateFlow<AccessRevision> = mutableState.asStateFlow()

    @Synchronized
    fun refresh(): AccessRevision {
        val observed = permissionReader.read()
        val current = mutableState.value
        if (observed != current.permission) {
            mutableState.value = AccessRevision(Math.addExact(current.value, 1), observed)
        }
        return mutableState.value
    }

    /**
     * Advances the capability epoch even when Android reports the same permission flags.
     *
     * With selected-photo access the user can replace the selected set while
     * READ_MEDIA_VISUAL_USER_SELECTED remains granted. Call this after the permission or
     * selection contract returns so work pinned to the previous set cannot be published.
     */
    @Synchronized
    fun invalidate(): AccessRevision {
        val current = mutableState.value
        val observed = permissionReader.read()
        val invalidated = AccessRevision(Math.addExact(current.value, 1), observed)
        mutableState.value = invalidated
        return invalidated
    }

    fun pin(): AccessRevision = mutableState.value

    fun isCurrent(pin: AccessRevision): Boolean = mutableState.value == pin
}
