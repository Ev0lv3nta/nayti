package app.nayti.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.nayti.R
import app.nayti.indexer.ModelPackRuntimeState
import app.nayti.ml.runtime.pack.ModelPackFailureReason
import app.nayti.ml.runtime.pack.ModelPackImportStage

@Composable
internal fun modelPackImportMessage(state: ModelPackRuntimeState): String = stringResource(
    if (state.cancelRequested) R.string.pack_stage_cancelling else when (state.importStage) {
        ModelPackImportStage.Reading, null -> R.string.pack_stage_reading
        ModelPackImportStage.Verifying -> R.string.pack_stage_verifying
        ModelPackImportStage.TestingModels -> R.string.pack_stage_testing
        ModelPackImportStage.Publishing -> R.string.pack_stage_publishing
    },
)

@Composable
internal fun modelPackFailureMessage(state: ModelPackRuntimeState): String {
    val reason = stringResource(when (state.failureReason) {
        ModelPackFailureReason.Signature -> R.string.pack_error_signature
        ModelPackFailureReason.Incompatible -> R.string.pack_error_incompatible
        ModelPackFailureReason.Storage -> R.string.pack_error_storage
        ModelPackFailureReason.Io -> R.string.pack_error_io
        ModelPackFailureReason.Runtime -> R.string.pack_error_runtime
        ModelPackFailureReason.ModelValidation -> R.string.pack_error_validation
        ModelPackFailureReason.InvalidFile, null -> R.string.setup_pack_failed
    })
    return if (state.installed == null) reason else reason + " " + stringResource(R.string.pack_previous_available)
}
