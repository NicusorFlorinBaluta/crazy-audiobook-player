package voice.features.bookOverview.views

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import voice.features.bookOverview.overview.BookOverviewCategory

@Composable
internal fun Header(
  category: BookOverviewCategory,
  modifier: Modifier = Modifier,
) {
  val title = if (category.customTitle != null) {
    category.customTitle
  } else if (category.nameRes != null) {
    stringResource(id = category.nameRes)
  } else {
    category.name
  }
  Text(
    modifier = modifier,
    text = title,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
  )
}
