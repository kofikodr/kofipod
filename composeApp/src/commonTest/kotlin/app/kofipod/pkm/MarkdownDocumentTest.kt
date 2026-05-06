package app.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownDocumentTest {
    @Test
    fun renderProducesYamlFrontmatterAndBody() {
        val doc =
            MarkdownDocument(
                frontmatter =
                    listOf(
                        "podcast" to "Locked On Broncos",
                        "episode" to "FCC bans routers",
                        "kofipodId" to "snip-abc",
                    ),
                body = "Body line.\n\nSecond paragraph.",
                filename = "snip-abc.md",
            )

        val expected =
            """
            ---
            podcast: "Locked On Broncos"
            episode: "FCC bans routers"
            kofipodId: "snip-abc"
            ---

            Body line.

            Second paragraph.

            """.trimIndent()

        assertEquals(expected, doc.render())
    }

    @Test
    fun renderEscapesQuotesAndBackslashesInValues() {
        val doc =
            MarkdownDocument(
                frontmatter = listOf("title" to """She said "hi" \ goodbye"""),
                body = "x",
                filename = "x.md",
            )
        val expected =
            """
            ---
            title: "She said \"hi\" \\ goodbye"
            ---

            x

            """.trimIndent()
        assertEquals(expected, doc.render())
    }

    @Test
    fun renderHandlesEmptyFrontmatter() {
        val doc =
            MarkdownDocument(
                frontmatter = emptyList(),
                body = "body only",
                filename = "x.md",
            )
        assertEquals("body only\n", doc.render())
    }

    @Test
    fun frontmatterPreservesInsertionOrder() {
        val doc =
            MarkdownDocument(
                frontmatter =
                    listOf(
                        "z" to "1",
                        "a" to "2",
                        "m" to "3",
                    ),
                body = "",
                filename = "x.md",
            )
        val rendered = doc.render()
        val keysInOrder =
            Regex("""^(\w+):""", RegexOption.MULTILINE)
                .findAll(rendered)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(listOf("z", "a", "m"), keysInOrder)
    }
}
