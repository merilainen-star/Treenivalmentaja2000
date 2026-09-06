package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a report's own structure survives the trip to the screen.
 *
 * The card used to print the model's headings as literal "##", which turned the one thing the
 * report is organised around — fact, then reading, then advice — into punctuation.
 */
class ProgramReportBlocksTest {

  @Test
  fun `a heading becomes a heading and the prose under it a paragraph`() {
    val blocks = programReportBlocks("## Tulkinta\nKevyet juoksut kulkivat nopeammin.")

    assertEquals(
      listOf(
        ProgramReportBlock.Heading("Tulkinta"),
        ProgramReportBlock.Paragraph("Kevyet juoksut kulkivat nopeammin."),
      ),
      blocks,
    )
  }

  /** Lines inside one paragraph stay together; a blank line is what ends it. */
  @Test
  fun `a blank line separates paragraphs and consecutive lines do not`() {
    val blocks = programReportBlocks("Rivi yksi\nrivi kaksi\n\nToinen kappale")

    assertEquals(
      listOf(
        ProgramReportBlock.Paragraph("Rivi yksi\nrivi kaksi"),
        ProgramReportBlock.Paragraph("Toinen kappale"),
      ),
      blocks,
    )
  }

  /** A run of blank lines is one break, not several empty paragraphs. */
  @Test
  fun `repeated blank lines collapse`() {
    val blocks = programReportBlocks("Eka\n\n\n\nToka")

    assertEquals(2, blocks.size)
  }

  /** A deeper heading is still a section break, and hiding it as body text would lose it. */
  @Test
  fun `a heading at any depth is still a heading`() {
    val blocks = programReportBlocks("### Alussa → lopussa\nTahti 6:05 → 5:47")

    assertEquals(ProgramReportBlock.Heading("Alussa → lopussa"), blocks.first())
  }

  /** A model that writes no headings at all still renders — as one paragraph, which is honest. */
  @Test
  fun `prose with no headings is one paragraph`() {
    val blocks = programReportBlocks("Pelkkää tekstiä ilman otsikoita.")

    assertEquals(listOf(ProgramReportBlock.Paragraph("Pelkkää tekstiä ilman otsikoita.")), blocks)
  }

  @Test
  fun `an empty report renders nothing rather than an empty block`() {
    assertTrue(programReportBlocks("").isEmpty())
    assertTrue(programReportBlocks("\n\n  \n").isEmpty())
  }
}
