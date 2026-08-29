package voice.core.playback.session

import android.app.Application
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.Inject
import java.io.File

@Inject
class ImageFileProvider(private val application: Application) {

  internal fun uri(file: File): Uri {
    val relativePath = try {
      file.canonicalFile.relativeTo(application.filesDir.canonicalFile).path.replace('\\', '/')
    } catch (_: Exception) {
      file.name
    }

    val uri = Uri.Builder()
      .scheme("content")
      .authority("${application.packageName}.coverprovider")
      .encodedPath("/$relativePath")
      .build()

    listOf(
      "com.android.systemui",
      "com.google.android.autosimulator",
      "com.google.android.carassistant",
      "com.google.android.googlequicksearchbox",
      "com.google.android.projection.gearhead",
      "com.google.android.wearable.app",
      "com.google.android.gms",
      "com.android.bluetooth",
      "com.google.android.apps.automotive.templates.host",
      "com.google.android.embedded.projection",
      "com.google.android.apps.gmm",
      "com.android.car.media",
    ).forEach { grantedPackage ->
      try {
        application.grantUriPermission(
          grantedPackage,
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      } catch (_: Exception) {}
    }

    return uri
  }
}
