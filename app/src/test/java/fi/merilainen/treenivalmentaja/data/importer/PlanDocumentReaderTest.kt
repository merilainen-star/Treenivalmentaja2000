package fi.merilainen.treenivalmentaja.data.importer

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanDocumentReaderTest {
  @Test fun `bounded document read accepts BOM and rejects oversized input`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val uri = Uri.parse("content://documents/plan.json")
    val resolver = context.contentResolver
    val shadow = org.robolectric.Shadows.shadowOf(resolver)
    shadow.registerInputStream(uri, java.io.ByteArrayInputStream("\uFEFF{}".toByteArray()))
    assertEquals("{}", PlanDocumentReader.read(resolver, uri))
    shadow.registerInputStream(uri, java.io.ByteArrayInputStream(ByteArray(PlanDocumentReader.MAX_BYTES + 1)))
    try { PlanDocumentReader.read(resolver, uri); fail("oversized input accepted") }
    catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("4 Mt")) }
  }

  @Test fun `accepts document open and shared stream but never private files or web links`() {
    val uri = Uri.parse("content://documents/plan.json")
    assertEquals(uri, PlanDocumentReader.uri(Intent(Intent.ACTION_VIEW, uri)))
    assertEquals(uri, PlanDocumentReader.uri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
    assertNull(PlanDocumentReader.uri(Intent(Intent.ACTION_VIEW, Uri.parse("file:///data/data/private"))))
    assertNull(PlanDocumentReader.uri(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/plan.json"))))
    assertNull(PlanDocumentReader.uri(Intent(Intent.ACTION_MAIN, uri)))
  }
}
