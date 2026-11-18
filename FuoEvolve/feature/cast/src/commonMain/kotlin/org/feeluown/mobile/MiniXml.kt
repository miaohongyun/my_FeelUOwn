package org.feeluown.mobile

/**
 * A deliberately small XML tree, enough for UPnP device descriptions.
 *
 * The descriptions are machine generated and use a narrow tag set, so a tolerant scanner is
 * enough and keeps the cast feature free of extra dependencies.
 */
internal data class XmlNode(
    val name: String,
    val qualifiedName: String = name,
    val attributes: Map<String, String> = emptyMap(),
    val children: MutableList<XmlNode> = mutableListOf(),
    var text: String = "",
) {
    fun child(localName: String): XmlNode? = children.firstOrNull { it.name == localName }

    fun childText(localName: String): String = child(localName)?.text?.trim().orEmpty()

    /** All descendants with the given local tag name, in document order. */
    fun descendants(localName: String): List<XmlNode> = buildList {
        children.forEach { child ->
            if (child.name == localName) add(child)
            addAll(child.descendants(localName))
        }
    }
}

internal object MiniXml {
    /** Parses [xml] into a tree, or returns `null` when the document is unusable. */
    fun parse(xml: String): XmlNode? {
        val root = XmlNode(name = "#document")
        val stack = ArrayDeque<XmlNode>()
        stack.addLast(root)
        var index = 0
        val length = xml.length
        while (index < length) {
            val next = xml.indexOf('<', index)
            if (next < 0) break
            if (next > index) {
                val chunk = xml.substring(index, next)
                if (chunk.isNotBlank()) {
                    stack.last().text += decodeEntities(chunk)
                }
            }
            index = next
            when {
                xml.startsWith("<!--", index) -> {
                    val end = xml.indexOf("-->", index)
                    index = if (end < 0) length else end + 3
                }
                xml.startsWith("<?", index) -> {
                    val end = xml.indexOf("?>", index)
                    index = if (end < 0) length else end + 2
                }
                xml.startsWith("<![CDATA[", index) -> {
                    val end = xml.indexOf("]]>", index)
                    val body = if (end < 0) xml.substring(index + 9) else xml.substring(index + 9, end)
                    stack.last().text += body
                    index = if (end < 0) length else end + 3
                }
                xml.startsWith("<!", index) -> {
                    val end = xml.indexOf('>', index)
                    index = if (end < 0) length else end + 1
                }
                xml.startsWith("</", index) -> {
                    val end = xml.indexOf('>', index)
                    if (end < 0) break
                    val name = xml.substring(index + 2, end).trim()
                    if (stack.size > 1) {
                        val current = stack.last()
                        if (current.qualifiedName == name) stack.removeLast()
                    }
                    index = end + 1
                }
                else -> {
                    val end = findTagEnd(xml, index)
                    if (end < 0) break
                    val raw = xml.substring(index + 1, end)
                    index = end + 1
                    val selfClosing = raw.endsWith("/")
                    val body = if (selfClosing) raw.dropLast(1) else raw
                    val name = body.substringBefore(' ').trim()
                    if (name.isEmpty()) continue
                    val node = XmlNode(
                        name = localName(name),
                        qualifiedName = name,
                        attributes = parseAttributes(body),
                    )
                    stack.last().children.add(node)
                    if (!selfClosing) stack.addLast(node)
                }
            }
        }
        return root.children.firstOrNull()
    }

    private fun findTagEnd(xml: String, start: Int): Int {
        var index = start + 1
        var quote: Char? = null
        while (index < xml.length) {
            val char = xml[index]
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return index
            }
            index++
        }
        return -1
    }

    private fun localName(name: String): String = name.substringAfterLast(':')

    private fun parseAttributes(body: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        val pattern = Regex("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        pattern.findAll(body).forEach { match ->
            val name = match.groupValues[1]
            val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
            attributes[name] = decodeEntities(value)
        }
        return attributes
    }

    private fun decodeEntities(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
