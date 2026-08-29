package voice.features.playbackScreen.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import voice.features.playbackScreen.PlayerDisplayMode

@Composable
internal fun PlayerViewModeSwitcher(
  currentMode: PlayerDisplayMode,
  onModeSelect: (PlayerDisplayMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .padding(horizontal = 24.dp, vertical = 6.dp)
      .height(36.dp)
      .clip(RoundedCornerShape(18.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ModePill(
        label = "Cover",
        isSelected = currentMode == PlayerDisplayMode.Cover,
        onClick = { onModeSelect(PlayerDisplayMode.Cover) },
        modifier = Modifier.weight(1f),
      )
      ModePill(
        label = "Script",
        isSelected = currentMode == PlayerDisplayMode.Lyrics,
        onClick = { onModeSelect(PlayerDisplayMode.Lyrics) },
        modifier = Modifier.weight(1f),
      )
      ModePill(
        label = "Reader",
        isSelected = currentMode == PlayerDisplayMode.Reader,
        onClick = { onModeSelect(PlayerDisplayMode.Reader) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ModePill(
  label: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val backgroundColor by animateColorAsState(
    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
    animationSpec = tween(durationMillis = 200),
    label = "modePillBg",
  )
  val textColor by animateColorAsState(
    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    animationSpec = tween(durationMillis = 200),
    label = "modePillText",
  )

  Box(
    modifier = modifier
      .fillMaxHeight()
      .padding(2.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(backgroundColor)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      color = textColor,
      fontSize = 13.sp,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
    )
  }
}
