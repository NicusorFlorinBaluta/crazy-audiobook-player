package voice.core.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CrazyClientFactoryTest {

  private val factory = CrazyClientFactory()

  @Test
  fun `parseUrlAndAuth handles plain url without auth`() {
    val (url, auth) = factory.parseUrlAndAuth("http://192.168.50.180:8000")
    assertEquals(expected = "http://192.168.50.180:8000/", actual = url)
    assertNull(auth)
  }

  @Test
  fun `parseUrlAndAuth adds http scheme if missing`() {
    val (url, auth) = factory.parseUrlAndAuth("192.168.50.180:8000/api")
    assertEquals(expected = "http://192.168.50.180:8000/api/", actual = url)
    assertNull(auth)
  }

  @Test
  fun `parseUrlAndAuth extracts credentials and strips them from url`() {
    val (url, auth) = factory.parseUrlAndAuth("https://myuser:mypass@crazyha.mywire.org/bookplayer")
    assertEquals(expected = "https://crazyha.mywire.org/bookplayer/", actual = url)
    assertNotNull(auth)
    // base64 of "myuser:mypass"
    val expectedBase64 = java.util.Base64.getEncoder().encodeToString("myuser:mypass".toByteArray())
    assertEquals(expected = "Basic $expectedBase64", actual = auth)
  }

  @Test
  fun `parseUrlAndAuth normalizes trailing slashes`() {
    val (url1, _) = factory.parseUrlAndAuth("https://example.com/books/")
    assertEquals(expected = "https://example.com/books/", actual = url1)

    val (url2, _) = factory.parseUrlAndAuth("https://example.com/books")
    assertEquals(expected = "https://example.com/books/", actual = url2)
  }
}
