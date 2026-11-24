package uz.tiyin.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the first worksheet of an .xlsx as rows of cell text. Apache POI would add megabytes to the
 * APK and StAX-based readers do not run on Android, while bank exports are a single plain sheet.
 */
object Xlsx {
    private const val MAX_PART_BYTES = 64L * 1024 * 1024

    class NotXlsx(message: String) : Exception(message)

    fun firstSheet(input: InputStream): List<List<String>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                    parts[e.name] = readLimited(zip)
                }
            }
        }
        val sheet = parts.keys.filter { it.startsWith("xl/worksheets/") }
            .minByOrNull { it.filter(Char::isDigit).toInt() }
            ?: throw NotXlsx("no worksheet")
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        return rows(parts.getValue(sheet), shared)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) throw NotXlsx("sheet too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        // Android's parser rejects some features the desktop JVM accepts, so hardening is best effort.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.directChildren(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.tagName == tag) out += n
            n = n.nextSibling
        }
        return out
    }

    /** Rich-text strings split into runs, and phonetic hints (`rPh`) must not leak into the value. */
    private fun textOf(si: Element): String =
        si.children("t").filter { (it.parentNode as Element).tagName != "rPh" }.joinToString("") { it.textContent }

    private fun sharedStrings(bytes: ByteArray): List<String> = parse(bytes).directChildren("si").map(::textOf)

    private fun column(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return col - 1
    }

    private fun rows(bytes: ByteArray, shared: List<String>): List<List<String>> =
        parse(bytes).children("row").map { row ->
            val cells = sortedMapOf<Int, String>()
            var next = 0
            for (c in row.directChildren("c")) {
                val idx = c.getAttribute("r").takeIf { it.isNotEmpty() }?.let(::column) ?: next
                next = idx + 1
                val v = c.directChildren("v").firstOrNull()?.textContent
                cells[idx] = when (c.getAttribute("t")) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> c.directChildren("is").firstOrNull()?.let(::textOf) ?: ""
                    else -> v ?: ""
                }
            }
            val width = (cells.keys.maxOrNull() ?: -1) + 1
            List(width) { cells[it] ?: "" }
        }
}
