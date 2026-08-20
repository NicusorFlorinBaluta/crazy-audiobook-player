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
}
