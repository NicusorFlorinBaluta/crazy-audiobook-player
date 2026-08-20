package voice.core.data.remote

import voice.core.data.BookContent

public interface CrazySyncManager {
  public suspend fun syncCatalog(): Result<Int>
  public suspend fun syncProgressToServer(bookContent: BookContent)
}
