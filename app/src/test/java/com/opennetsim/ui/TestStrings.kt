package com.opennetsim.ui

import com.opennetsim.R
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resuelve [UiText] en los tests JVM leyendo los `strings.xml` del módulo, como haría Android: así los
 * tests comprueban los textos reales en español y en inglés, y que los argumentos encajan con cada formato.
 * Plurales: `one` para 1 y `other` para el resto (lo que usan el inglés y el español con estos recuentos).
 */
class TestStrings private constructor(
    private val strings: Map<String, String>,
    private val plurals: Map<String, Map<String, String>>
) {
    fun render(text: UiText): String = when (text) {
        is UiText.Raw -> text.text
        is UiText.Res -> format(strings.getValue(name(R.string::class.java, text.id)), text.args)
        is UiText.Plural -> {
            val forms = plurals.getValue(name(R.plurals::class.java, text.id))
            format(forms[if (text.count == 1) "one" else "other"] ?: forms.getValue("other"), text.args)
        }
    }

    private fun format(pattern: String, args: List<Any>): String {
        val resolved = args.map { if (it is UiText) render(it) else it }.toTypedArray()
        return if (resolved.isEmpty()) pattern else String.format(Locale.US, pattern, *resolved)
    }

    private fun name(resourceClass: Class<*>, id: Int): String =
        resourceClass.fields.first { it.getInt(null) == id }.name

    companion object {
        private val RES_DIR = File("src/main/res")

        val SPANISH: TestStrings by lazy { load("values-es") }
        val ENGLISH: TestStrings by lazy { load(null) }

        private fun load(qualifiedDir: String?): TestStrings {
            val strings = mutableMapOf<String, String>()
            val plurals = mutableMapOf<String, Map<String, String>>()
            // Primero el idioma por defecto (incluye los textos no traducibles) y encima el traducido.
            listOfNotNull("values", qualifiedDir).forEach { dir ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(File(RES_DIR, "$dir/strings.xml"))
                document.getElementsByTagName("string").elements().forEach {
                    strings[it.getAttribute("name")] = unescape(it.textContent)
                }
                document.getElementsByTagName("plurals").elements().forEach { plural ->
                    plurals[plural.getAttribute("name")] = plural.getElementsByTagName("item").elements()
                        .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                }
            }
            return TestStrings(strings, plurals)
        }

        private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

        private fun unescape(text: String): String = text.replace("\\'", "'").replace("\\\"", "\"")
    }
}

/** Texto en español, el de las specs. */
fun UiText?.es(): String? = this?.let { TestStrings.SPANISH.render(it) }

fun UiText?.en(): String? = this?.let { TestStrings.ENGLISH.render(it) }
