package voice.core.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

public interface CrazyAudiobookApi {

  @GET("api/mobile/v1/server-info")
  public suspend fun getServerInfo(): Response<CrazyServerInfoDto>

  @GET("api/mobile/v1/catalog")
  public suspend fun getCatalog(
    @Query("status") status: String = "all",
  ): Response<CrazyCatalogResponse>

  @GET("api/mobile/v1/books/{projectId}")
  public suspend fun getBookDetail(
    @Path("projectId") projectId: String,
  ): Response<CrazyBookDetailDto>

  @POST("api/mobile/v1/books/{projectId}/progress")
  public suspend fun saveProgress(
    @Path("projectId") projectId: String,
    @Body request: CrazyProgressRequest,
  ): Response<CrazyProgressResponse>

  @GET("api/mobile/v1/books/{projectId}/progress")
  public suspend fun getProgress(
    @Path("projectId") projectId: String,
  ): Response<CrazyProgressResponse>

  @GET("api/mobile/v1/books/{projectId}/chapters/{chapterNum}/lyrics")
  public suspend fun getChapterLyrics(
    @Path("projectId") projectId: String,
    @Path("chapterNum") chapterNum: Int,
  ): Response<CrazyChapterLyricsDto>

  @GET("api/mobile/v1/books/{projectId}/chapters/{chapterNum}/reader")
  public suspend fun getChapterReader(
    @Path("projectId") projectId: String,
    @Path("chapterNum") chapterNum: Int,
  ): Response<CrazyChapterReaderDto>

  @POST("api/mobile/v1/books/{projectId}/flags")
  public suspend fun flagPlaybackIssue(
    @Path("projectId") projectId: String,
    @Body request: CrazyPlaybackFlagRequest,
  ): Response<CrazyPlaybackFlagResponse>

  @GET("api/mobile/v1/books/{projectId}/flags")
  public suspend fun getPlaybackFlags(
    @Path("projectId") projectId: String,
  ): Response<CrazyPlaybackFlagsResponse>
}
