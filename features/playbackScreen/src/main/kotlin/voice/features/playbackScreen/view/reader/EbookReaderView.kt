package voice.features.playbackScreen.view.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import voice.core.data.remote.CrazyReaderParagraphDto
import voice.features.playbackScreen.ReaderTheme
import voice.features.playbackScreen.ReaderViewState
import kotlin.math.max

@Composable
internal fun EbookReaderView(
  readerState: ReaderViewState?,
  onParagraphClick: (Long) -> Unit,
  onUpdateTheme: (ReaderTheme) -> Unit,
  onUpdateFontSize: (Int) -> Unit,
  onToggleAutoFollow: (Boolean) -> Unit,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (readerState == null || readerState.isLoading) {
    Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Loading chapter text...",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    return
  }

  if (readerState.errorMessage != null && readerState.paragraphs.isEmpty()) {
    Box(
      modifier = modifier.fillMaxSize().padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "Reader not available offline",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = readerState.errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) {
          Text("Retry Sync")
        }
      }
    }
    return
  }

  if (readerState.paragraphs.isEmpty()) {
    Box(
      modifier = modifier.fillMaxSize().padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "No chapter text available.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  val themeColors = remember(readerState.theme) {
    getReaderThemeColors(readerState.theme)
  }

  var showControls by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val isDragged by listState.interactionSource.collectIsDraggedAsState()
  var userScrolledManually by remember { mutableStateOf(false) }

  if (isDragged) {
    userScrolledManually = true
  }

  LaunchedEffect(userScrolledManually) {
    if (userScrolledManually) {
      delay(7000)
      userScrolledManually = false
    }
  }

  // Auto-scroll when active paragraph changes
  LaunchedEffect(readerState.activeParagraphIndex, userScrolledManually, readerState.autoFollow) {
    if (readerState.autoFollow && !userScrolledManually && readerState.activeParagraphIndex >= 0) {
      val targetIndex = max(0, readerState.activeParagraphIndex)
      listState.animateScrollToItem(targetIndex)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(themeColors.backgroundColor),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Reader Quick Controls Bar
      ReaderToolbar(
        fontSizeSp = readerState.fontSizeSp,
        theme = readerState.theme,
        autoFollow = readerState.autoFollow,
        textColor = themeColors.textColor,
        onUpdateTheme = onUpdateTheme,
        onUpdateFontSize = onUpdateFontSize,
        onToggleAutoFollow = onToggleAutoFollow,
      )

      // Paragraphs List
      LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, start = 18.dp, end = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        if (readerState.title.isNotBlank()) {
          item(key = "chapter_header") {
            Text(
              text = readerState.title,
              color = themeColors.textColor,
              fontSize = (readerState.fontSizeSp + 4).sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Serif,
              modifier = Modifier.padding(bottom = 12.dp),
            )
          }
        }

        itemsIndexed(
          items = readerState.paragraphs,
          key = { idx, _ -> "p_$idx" },
        ) { index, paragraph ->
          val isActive = index == readerState.activeParagraphIndex
          ReaderParagraphItem(
            paragraph = paragraph,
            isActive = isActive,
            fontSizeSp = readerState.fontSizeSp,
            textColor = themeColors.textColor,
            activeBgColor = themeColors.activeParagraphBgColor,
            accentColor = themeColors.accentColor,
            onClick = { onParagraphClick(paragraph.startMs) },
          )
        }
      }
    }

    // Floating Sync Button when user scrolls away
    AnimatedVisibility(
      visible = userScrolledManually && readerState.activeParagraphIndex >= 0,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 16.dp),
    ) {
      FloatingActionButton(
        onClick = { userScrolledManually = false },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Follow Audio",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun ReaderParagraphItem(
  paragraph: CrazyReaderParagraphDto,
  isActive: Boolean,
  fontSizeSp: Int,
  textColor: Color,
  activeBgColor: Color,
  accentColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val background = if (isActive) activeBgColor else Color.Transparent
  val lineHeightSp = (fontSizeSp * 1.55).sp

  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(background)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Row {
      if (isActive) {
        Box(
          modifier = Modifier
            .width(3.dp)
            .height(lineHeightSp.value.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(accentColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
      }

      Text(
        text = paragraph.text,
        color = textColor,
        fontSize = fontSizeSp.sp,
        fontFamily = FontFamily.Serif,
        lineHeight = lineHeightSp,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
      )
    }
  }
}

@Composable
private fun ReaderToolbar(
  fontSizeSp: Int,
  theme: ReaderTheme,
  autoFollow: Boolean,
  textColor: Color,
  onUpdateTheme: (ReaderTheme) -> Unit,
  onUpdateFontSize: (Int) -> Unit,
  onToggleAutoFollow: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Font size controls
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable { if (fontSizeSp > 14) onUpdateFontSize(fontSizeSp - 2) }
          .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(text = "A-", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
      }
      Spacer(modifier = Modifier.width(4.dp))
      Text(text = "${fontSizeSp}sp", color = textColor.copy(alpha = 0.7f), fontSize = 11.sp)
      Spacer(modifier = Modifier.width(4.dp))
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable { if (fontSizeSp < 28) onUpdateFontSize(fontSizeSp + 2) }
          .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(text = "A+", color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
      }
    }

    // Theme Picker
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ThemeDot(
        color = Color(0xFFFFFFFF),
        isSelected = theme == ReaderTheme.Light,
        onClick = { onUpdateTheme(ReaderTheme.Light) },
      )
      ThemeDot(
        color = Color(0xFFF8F1E3), // Sepia
        isSelected = theme == ReaderTheme.Sepia,
        onClick = { onUpdateTheme(ReaderTheme.Sepia) },
      )
      ThemeDot(
        color = Color(0xFF262626), // Dark
        isSelected = theme == ReaderTheme.Dark,
        onClick = { onUpdateTheme(ReaderTheme.Dark) },
      )
      ThemeDot(
        color = Color(0xFF000000), // OLED
        isSelected = theme == ReaderTheme.Oled,
        onClick = { onUpdateTheme(ReaderTheme.Oled) },
      )
    }
  }
}

@Composable
private fun ThemeDot(
  color: Color,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(22.dp)
      .clip(CircleShape)
      .background(color)
      .border(
        width = if (isSelected) 2.dp else 1.dp,
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
        shape = CircleShape,
      )
      .clickable(onClick = onClick),
  )
}

private data class ReaderColors(
  val backgroundColor: Color,
  val textColor: Color,
  val activeParagraphBgColor: Color,
  val accentColor: Color,
)

private fun getReaderThemeColors(theme: ReaderTheme): ReaderColors {
  return when (theme) {
    ReaderTheme.Light -> ReaderColors(
      backgroundColor = Color(0xFFFAFAFA),
      textColor = Color(0xFF1F1F1F),
      activeParagraphBgColor = Color(0xFFE8F0FE),
      accentColor = Color(0xFF1A73E8),
    )
    ReaderTheme.Sepia -> ReaderColors(
      backgroundColor = Color(0xFFFBF0D9),
      textColor = Color(0xFF3D2E1E),
      activeParagraphBgColor = Color(0xFFF3E0BD),
      accentColor = Color(0xFF8D5B18),
    )
    ReaderTheme.Dark -> ReaderColors(
      backgroundColor = Color(0xFF1E1E1E),
      textColor = Color(0xFFE0E0E0),
      activeParagraphBgColor = Color(0xFF2E3B4E),
      accentColor = Color(0xFF64B5F6),
    )
    ReaderTheme.Oled -> ReaderColors(
      backgroundColor = Color(0xFF000000),
      textColor = Color(0xFFEEEEEE),
      activeParagraphBgColor = Color(0xFF1A2634),
      accentColor = Color(0xFF4FC3F7),
    )
  }
}
