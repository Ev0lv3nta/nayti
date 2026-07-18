package app.nayti.ui

enum class SetupNextAction {
    WAIT_FOR_MODEL_PACK,
    IMPORT_MODEL_PACK,
    REQUEST_PHOTO_ACCESS,
    WAIT_FOR_CATALOG,
    START_PREPARATION,
    WAIT_FOR_PREPARATION,
    ENTER_APP,
}

data class SetupSnapshot(
    val modelPackReady: Boolean,
    val modelPackBusy: Boolean,
    val photoAccessGranted: Boolean,
    val catalogReconciling: Boolean,
    val availablePhotos: Long,
    val indexingRunning: Boolean,
    val indexingAccessible: Long,
    val indexingOutstanding: Long,
)

object SetupPolicy {
    fun next(snapshot: SetupSnapshot): SetupNextAction = when {
        snapshot.modelPackBusy -> SetupNextAction.WAIT_FOR_MODEL_PACK
        !snapshot.modelPackReady -> SetupNextAction.IMPORT_MODEL_PACK
        !snapshot.photoAccessGranted -> SetupNextAction.REQUEST_PHOTO_ACCESS
        snapshot.catalogReconciling -> SetupNextAction.WAIT_FOR_CATALOG
        snapshot.availablePhotos == 0L -> SetupNextAction.ENTER_APP
        snapshot.indexingRunning -> SetupNextAction.WAIT_FOR_PREPARATION
        snapshot.indexingAccessible == 0L || snapshot.indexingOutstanding > 0L ->
            SetupNextAction.START_PREPARATION
        else -> SetupNextAction.ENTER_APP
    }
}
