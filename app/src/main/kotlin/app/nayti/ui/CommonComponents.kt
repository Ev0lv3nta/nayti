package app.nayti.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import app.nayti.platform.media.MediaKey
import app.nayti.ui.designsystem.icon.NaytiIcon
import app.nayti.ui.designsystem.icon.NaytiIconMark
import app.nayti.ui.designsystem.theme.NaytiSpacing
import app.nayti.ui.designsystem.theme.NaytiTheme

@Composable
internal fun ScreenHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NaytiSpacing.Small)) {
        Text(
            text = eyebrow.uppercase(),
            color = NaytiTheme.colors.accent,
            style = NaytiTheme.type.labelL,
        )
        Text(
            text = title,
            style = NaytiTheme.type.hero,
            color = NaytiTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = subtitle,
            style = NaytiTheme.type.bodyL,
            color = NaytiTheme.colors.inkMuted,
        )
    }
}

@Composable
internal fun PhotoThumbnail(
    key: MediaKey,
    accessRevision: Long,
    description: String?,
    onLoad: suspend (MediaKey, Long) -> Bitmap?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val bitmap by
        produceState<Bitmap?>(
            initialValue = null,
            key1 = key,
            key2 = accessRevision,
        ) {
            value = onLoad(key, accessRevision)
        }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = description,
            modifier = modifier.fillMaxSize().clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(NaytiTheme.colors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            NaytiIconMark(
                icon = NaytiIcon.Scene,
                color = NaytiTheme.colors.inkFaint,
            )
        }
    }
}
