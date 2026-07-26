package app.nayti.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.ui.designsystem.component.ChromeMaterial
import app.nayti.ui.designsystem.component.GlassSurface
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiChrome
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

@Composable
fun ShellStatusBar(
    status: ShellStatusUi,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
    material: ChromeMaterial = ChromeMaterial.Solid,
) {
    val label = stringResource(status.message.stringResource)
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NaytiChrome.StatusStripHeight)
            .then(
                if (status.actionable) {
                    Modifier
                        .clickable(onClick = onOpenDetails)
                        .semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = label
            },
        material = material,
        hairlineOnTop = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            NaytiIconMark(
                icon = status.tone.icon,
                color = status.tone.color(),
                size = 20.dp,
            )
            Spacer(Modifier.width(NaytiSpacing.Small))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = NaytiTheme.colors.ink,
                style = NaytiTheme.type.labelL,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.actionable) {
                Spacer(Modifier.width(NaytiSpacing.XSmall))
                NaytiIconMark(
                    icon = NaytiIcon.ChevronRight,
                    color = NaytiTheme.colors.inkMuted,
                    size = 24.dp,
                )
            }
        }
    }
}

private val ShellStatusMessage.stringResource: Int
    get() = when (this) {
        ShellStatusMessage.CheckingLibrary -> R.string.shell_status_checking_library
        ShellStatusMessage.NeedsAccess -> R.string.shell_status_needs_access
        ShellStatusMessage.NeedsModels -> R.string.shell_status_needs_models
        ShellStatusMessage.CheckingModels -> R.string.shell_status_checking_models
        ShellStatusMessage.InstallingModels -> R.string.shell_status_installing_models
        ShellStatusMessage.ModelsFailed -> R.string.shell_status_models_failed
        ShellStatusMessage.LibraryFailed -> R.string.shell_status_library_failed
        ShellStatusMessage.SearchReadyPreparing -> R.string.shell_status_search_ready_preparing
        ShellStatusMessage.Preparing -> R.string.shell_status_preparing
        ShellStatusMessage.ReadyToPrepare -> R.string.shell_status_ready_to_prepare
        ShellStatusMessage.SearchAvailable -> R.string.shell_status_search_available
        ShellStatusMessage.PreparedPartially -> R.string.shell_status_prepared_partially
        ShellStatusMessage.PausedByUser -> R.string.shell_status_paused_by_user
        ShellStatusMessage.PausedBySystem -> R.string.shell_status_paused_by_system
        ShellStatusMessage.PausedByConstraint -> R.string.shell_status_paused_by_constraint
        ShellStatusMessage.Completed -> R.string.shell_status_completed
        ShellStatusMessage.CompletedWithGaps -> R.string.shell_status_completed_with_gaps
        ShellStatusMessage.PreparationFailed -> R.string.shell_status_preparation_failed
    }

private val ShellStatusTone.icon: NaytiIcon
    get() = when (this) {
        ShellStatusTone.Neutral -> NaytiIcon.Info
        ShellStatusTone.Ready -> NaytiIcon.Check
        ShellStatusTone.Attention -> NaytiIcon.Alert
        ShellStatusTone.Error -> NaytiIcon.Alert
    }

@Composable
private fun ShellStatusTone.color(): Color = when (this) {
    ShellStatusTone.Neutral -> NaytiTheme.colors.inkMuted
    ShellStatusTone.Ready -> NaytiTheme.colors.ready
    ShellStatusTone.Attention -> NaytiTheme.colors.attention
    ShellStatusTone.Error -> NaytiTheme.colors.error
}
