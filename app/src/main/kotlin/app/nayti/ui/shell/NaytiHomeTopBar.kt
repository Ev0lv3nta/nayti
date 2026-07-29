package app.nayti.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nayti.R
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

@Composable
fun NaytiHomeTopBar(
    status: ShellStatusUi,
    onOpenReadiness: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusDescription = stringResource(status.message.stringResource)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NaytiTheme.colors.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = NaytiSpacing.Screen, vertical = NaytiSpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = stringResource(R.string.shell_redesign_wordmark),
                style = NaytiTheme.type.titleL,
                fontWeight = FontWeight.SemiBold,
                color = NaytiTheme.colors.ink,
            )
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenReadiness)
                    .semantics {
                        role = Role.Button
                        stateDescription = statusDescription
                    },
                shape = RoundedCornerShape(16.dp),
                color = NaytiTheme.colors.surfaceHigh,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = NaytiTheme.colors.hairline,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = NaytiSpacing.Medium,
                        vertical = NaytiSpacing.Small,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NaytiIconMark(
                        icon = status.tone.icon,
                        color = status.tone.indicatorColor(),
                        size = 18.dp,
                    )
                    Spacer(Modifier.width(NaytiSpacing.XSmall))
                    Text(
                        text = stringResource(R.string.shell_redesign_readiness),
                        style = NaytiTheme.type.labelL,
                        color = NaytiTheme.colors.ink,
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                NaytiIconMark(
                    icon = NaytiIcon.Settings,
                    color = NaytiTheme.colors.inkMuted,
                )
            }
        }
    }
}

private val ShellStatusTone.icon: NaytiIcon
    get() = when (this) {
        ShellStatusTone.Neutral -> NaytiIcon.Info
        ShellStatusTone.Ready -> NaytiIcon.Check
        ShellStatusTone.Attention,
        ShellStatusTone.Error,
        -> NaytiIcon.Alert
    }

@Composable
private fun ShellStatusTone.indicatorColor(): Color =
    when (this) {
        ShellStatusTone.Neutral -> NaytiTheme.colors.inkMuted
        ShellStatusTone.Ready -> NaytiTheme.colors.ready
        ShellStatusTone.Attention -> NaytiTheme.colors.attention
        ShellStatusTone.Error -> NaytiTheme.colors.error
    }
