package jp.rimtty.codematch.feature.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocaleResourceParityTest {
    @Test
    fun japaneseAndEnglishResourcesHaveMatchingKeysQuantitiesAndPlaceholders() {
        val japanese = resourceShape(resourceFile("values"))
        val english = resourceShape(resourceFile("values-en"))

        assertEquals(japanese.keys, english.keys)
        assertEquals(japanese.pluralQuantities, english.pluralQuantities)
        assertEquals(japanese.formatTokens, english.formatTokens)
        assertTrue(japanese.nonBlank)
        assertTrue(english.nonBlank)
    }

    private data class ResourceShape(
        val keys: Set<String>,
        val pluralQuantities: Map<String, Set<String>>,
        val formatTokens: Map<String, List<String>>,
        val nonBlank: Boolean,
    )

    private fun resourceFile(directory: String): File {
        val relativePath = "src/main/res/$directory/strings.xml"
        val projectFile = File(relativePath)
        if (projectFile.isFile) return projectFile

        var root = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(root, "android/feature/settings/$relativePath")
            if (candidate.isFile) return candidate
            root = root.parentFile ?: break
        }
        error("Unable to locate settings resource: $relativePath")
    }

    private fun resourceShape(file: File): ResourceShape {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val entries = listOf("string", "plurals").flatMap { resourceType ->
            val nodes = document.getElementsByTagName(resourceType)
            (0 until nodes.length).map { index ->
                resourceType to (nodes.item(index) as Element)
            }
        }
        val keys = entries.map { (type, element) -> "$type:${element.getAttribute("name")}" }.toSet()
        val pluralQuantities = entries
            .filter { (type, _) -> type == "plurals" }
            .associate { (_, element) ->
                element.getAttribute("name") to element
                    .getElementsByTagName("item")
                    .let { items ->
                        (0 until items.length)
                            .map { index -> (items.item(index) as Element).getAttribute("quantity") }
                            .toSet()
                    }
            }
        val formatTokens = entries.associate { (type, element) ->
            "$type:${element.getAttribute("name")}" to FORMAT_TOKEN_PATTERN
                .findAll(element.textContent.orEmpty())
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
        }
        return ResourceShape(
            keys = keys,
            pluralQuantities = pluralQuantities,
            formatTokens = formatTokens,
            nonBlank = entries.all { (_, element) -> element.textContent.orEmpty().isNotBlank() },
        )
    }

    private companion object {
        val FORMAT_TOKEN_PATTERN: Regex = Regex("%\\d+\\$[a-zA-Z]")
    }
}
