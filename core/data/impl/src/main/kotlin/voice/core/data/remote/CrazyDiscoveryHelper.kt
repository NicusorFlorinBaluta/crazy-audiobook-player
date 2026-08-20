package voice.core.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Inject
@SingleIn(AppScope::class)
public class CrazyDiscoveryHelper(
  context: Context,
) {

  private val nsdManager: NsdManager? =
    context.getSystemService(Context.NSD_SERVICE) as? NsdManager

  @Suppress("DEPRECATION")
  public fun discoverServers(): Flow<String> = callbackFlow {
    if (nsdManager == null) {
      close()
      return@callbackFlow
    }

    val discoveryListener = object : NsdManager.DiscoveryListener {
      override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
        nsdManager.stopServiceDiscovery(this)
      }

      override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
        nsdManager.stopServiceDiscovery(this)
      }

      override fun onDiscoveryStarted(serviceType: String?) {}

      override fun onDiscoveryStopped(serviceType: String?) {}

      override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
        serviceInfo?.let { info ->
          nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
              resolvedInfo?.host?.hostAddress?.let { host ->
                val port = resolvedInfo.port
                trySend("http://$host:$port")
              }
            }
          })
        }
      }

      override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
    }

    try {
      nsdManager.discoverServices(
        "_crazy-audiobook._tcp.",
        NsdManager.PROTOCOL_DNS_SD,
        discoveryListener,
      )
    } catch (_: Exception) {}

    awaitClose {
      try {
        nsdManager.stopServiceDiscovery(discoveryListener)
      } catch (_: Exception) {}
    }
  }
}
