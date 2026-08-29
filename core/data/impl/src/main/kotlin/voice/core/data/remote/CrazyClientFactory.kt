package voice.core.data.remote

import android.util.Base64
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Inject
@SingleIn(AppScope::class)
public class CrazyClientFactory {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
  }

  public fun create(rawBaseUrl: String): CrazyAudiobookApi {
    val (sanitizedUrl, basicAuthHeader) = parseUrlAndAuth(rawBaseUrl)
    val okHttpClient = buildOkHttpClient(basicAuthHeader)

    val contentType = "application/json".toMediaType()

    return Retrofit.Builder()
      .baseUrl(sanitizedUrl)
      .client(okHttpClient)
      .addConverterFactory(json.asConverterFactory(contentType))
      .build()
      .create(CrazyAudiobookApi::class.java)
  }

  public fun createOkHttpClient(rawBaseUrl: String): OkHttpClient {
    val (_, basicAuthHeader) = parseUrlAndAuth(rawBaseUrl)
    return buildOkHttpClient(basicAuthHeader)
  }

  public fun parseUrlAndAuth(rawUrl: String): Pair<String, String?> {
    var url = rawUrl.trim()
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      url = "http://$url"
    }

    var authHeader: String? = null
    val parsed = url.toHttpUrlOrNull()

    if (parsed != null && (parsed.username.isNotEmpty() || parsed.password.isNotEmpty())) {
      val user = parsed.username
      val pass = parsed.password
      val creds = "$user:$pass"
      val encoded = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
      authHeader = "Basic $encoded"

      // Rebuild URL without credentials in userinfo
      val cleanUrl = parsed.newBuilder()
        .username("")
        .password("")
        .build()
        .toString()
      url = cleanUrl
    }

    val finalUrl = if (url.endsWith("/")) url else "$url/"
    return Pair(finalUrl, authHeader)
  }

  private fun buildOkHttpClient(basicAuthHeader: String?): OkHttpClient {
    val trustAllCerts = arrayOf<TrustManager>(
      object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }
    )

    val sslContext = SSLContext.getInstance("SSL").apply {
      init(null, trustAllCerts, SecureRandom())
    }

    val builder = OkHttpClient.Builder()
      .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
      .hostnameVerifier(HostnameVerifier { _, _ -> true })
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .followRedirects(true)
      .followSslRedirects(true)

    if (basicAuthHeader != null) {
      builder.addInterceptor(
        Interceptor { chain ->
          val original = chain.request()
          val newReq = original.newBuilder()
            .header("Authorization", basicAuthHeader)
            .build()
          chain.proceed(newReq)
        }
      )
    }

    return builder.build()
  }
}
