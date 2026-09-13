package fi.merilainen.treenivalmentaja.data.importer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/** Only a granted document is read; an external intent never activates a plan. */
object PlanDocumentReader {
  const val MAX_BYTES = 4 * 1024 * 1024

  fun uri(intent: Intent): Uri? {
    val uri = when (intent.action) {
      Intent.ACTION_VIEW -> intent.data
      Intent.ACTION_SEND -> {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
      }
      else -> null
    }
    // Never let another app ask us to read our own private files via file://.
    return uri?.takeIf { it.scheme == "content" }
  }

  fun read(resolver: ContentResolver, uri: Uri): String {
    require(uri.scheme == "content") { "Valitse tiedosto Androidin tiedostonvalitsimella." }
    return requireNotNull(resolver.openInputStream(uri)) { "Tiedostoa ei voitu avata." }.use { input ->
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_BYTES) { "Ohjelmatiedosto on liian suuri (enintään 4 Mt)." }
        output.write(buffer, 0, count)
      }
      output.toString("UTF-8").removePrefix("\uFEFF")
    }
  }
}
