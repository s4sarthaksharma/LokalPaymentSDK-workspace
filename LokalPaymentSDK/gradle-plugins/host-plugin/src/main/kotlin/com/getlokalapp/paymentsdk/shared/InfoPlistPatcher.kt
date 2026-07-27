package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val QUERIES_SCHEMES_KEY = "LSApplicationQueriesSchemes"

/**
 * Merges [schemes] into `LSApplicationQueriesSchemes` of the host's `Info.plist` when the
 * host opted in via `lokalPaymentSdk { iosInfoPlist = … }`; returns whether the plist is
 * SDK-managed (i.e. the property was set), regardless of whether this run actually added
 * anything new. Unset → returns false and the plist is left untouched, the schemes being
 * surfaced as an `INTEGRATION.md` note instead (see [writeIntegrationNotes]). The
 * SPM-flavored replacement for the CocoaPods-era `post_install` snippet that patched the
 * plist via `Xcodeproj::Plist`. Fails loudly if the configured path isn't a file, since an
 * explicit opt-in with a wrong path would otherwise silently do nothing.
 */
internal fun patchInfoPlistIfConfigured(
    project: Project,
    config: LokalPaymentSdkExtension,
    schemes: List<String>,
): Boolean {
    val path = config.iosInfoPlist ?: return false
    val plistFile = project.file(path)
    if (!plistFile.isFile) {
        throw GradleException(
            "lokalPaymentSdk { iosInfoPlist = \"$path\" } does not resolve to a file " +
                "(looked at $plistFile). Point it at the committed Info.plist your app " +
                "target uses, or remove the property to add the UPI query schemes by hand.",
        )
    }
    val added = mergeQueriesSchemes(plistFile, schemes)
    if (added > 0) {
        project.logger.lifecycle(
            "LokalPaymentSDK: merged $added UPI query scheme(s) into $plistFile",
        )
    }
    return true
}

/**
 * Idempotently adds [schemes] as `<string>` entries under the `LSApplicationQueriesSchemes`
 * array in [plistFile], creating the key/array if absent. Returns how many were newly added
 * (0 → already current, and the file is left untouched). Preserves the file's existing
 * formatting: parses with whitespace retained, appends only the new nodes with matching
 * indentation, and writes back without re-indenting — so a plain sync is a zero-line diff
 * and adding a scheme shows only the added lines. DTD loading is disabled so parsing never
 * reaches out to apple.com for the plist DOCTYPE.
 */
private fun mergeQueriesSchemes(plistFile: File, schemes: List<String>): Int {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val builder = factory.newDocumentBuilder().apply {
        // The plist DOCTYPE points at apple.com; resolve it to nothing so parsing stays offline.
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
    val doc = builder.parse(plistFile)

    val rootDict = elementChildren(doc.documentElement).firstOrNull { it.tagName == "dict" }
        ?: error("$plistFile has no <dict> under <plist> — not a well-formed Info.plist.")

    // dict children are indented one unit; array items one unit deeper.
    val keyIndent = leadingWhitespace(rootDict) ?: "\n\t"
    val itemIndent = keyIndent + "\t"

    // Locate the LSApplicationQueriesSchemes <array> — the element sibling right after its
    // <key> — or synthesize the key/array pair at the end of the dict if it's absent.
    val dictChildren = elementChildren(rootDict)
    val keyIndex = dictChildren.indexOfFirst {
        it.tagName == "key" && it.textContent == QUERIES_SCHEMES_KEY
    }
    val array: Element = dictChildren.getOrNull(keyIndex + 1)
        ?.takeIf { keyIndex >= 0 && it.tagName == "array" }
        ?: run {
            val newArray = doc.createElement("array")
            val newKey = doc.createElement("key").apply { textContent = QUERIES_SCHEMES_KEY }
            val dictTail = trailingWhitespace(rootDict)
            insertBeforeOrAppend(rootDict, doc.createTextNode(keyIndent), dictTail)
            insertBeforeOrAppend(rootDict, newKey, dictTail)
            insertBeforeOrAppend(rootDict, doc.createTextNode(keyIndent), dictTail)
            insertBeforeOrAppend(rootDict, newArray, dictTail)
            newArray
        }

    val existing = elementChildren(array)
        .filter { it.tagName == "string" }
        .map { it.textContent }
        .toMutableSet()

    val arrayTail = trailingWhitespace(array) // the "\n\t" before </array>, if the array had entries
    var added = 0
    for (scheme in schemes) {
        if (!existing.add(scheme)) continue
        insertBeforeOrAppend(array, doc.createTextNode(itemIndent), arrayTail)
        insertBeforeOrAppend(array, doc.createElement("string").apply { textContent = scheme }, arrayTail)
        added++
    }
    // A freshly-created (previously empty) array has no closing-indent text node, so add one
    // — otherwise </array> would glue onto the last <string>.
    if (arrayTail == null && added > 0) array.appendChild(doc.createTextNode(keyIndent))

    if (added == 0) return 0

    writePlist(doc, plistFile)
    return added
}

private fun writePlist(doc: Document, file: File) {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "-//Apple//DTD PLIST 1.0//EN")
        setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "http://www.apple.com/DTDs/PropertyList-1.0.dtd")
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        // We manage whitespace by hand (above), so leave re-indenting off for a minimal diff.
        setOutputProperty(OutputKeys.INDENT, "no")
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    // The transformer packs the prolog onto one line; restore the conventional plist layout
    // (declaration / DOCTYPE / <plist> each on their own line) so the top of the file matches
    // how Xcode writes it.
    var out = writer.toString()
        // Match Apple's declaration exactly — the transformer adds a `standalone` attribute
        // the original plist doesn't carry.
        .replace(" standalone=\"no\"?>", "?>")
        .replace("?><!DOCTYPE", "?>\n<!DOCTYPE")
        .replaceFirst(Regex("""("[^"]*PropertyList-1\.0\.dtd">)<plist"""), "$1\n<plist")
    if (!out.endsWith("\n")) out += "\n"
    file.writeText(out)
}

/** The element (non-text) children of [node], in document order. */
private fun elementChildren(node: Node): List<Element> {
    val out = ArrayList<Element>()
    val kids = node.childNodes
    for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(out::add)
    return out
}

/** The whitespace text node immediately before [parent]'s first element child (its indent), if any. */
private fun leadingWhitespace(parent: Node): String? {
    val kids = parent.childNodes
    for (i in 0 until kids.length) {
        val n = kids.item(i)
        if (n is Element) {
            val prev = n.previousSibling
            return prev?.takeIf { it.nodeType == Node.TEXT_NODE && it.textContent.isBlank() }
                ?.textContent
        }
    }
    return null
}

/** [parent]'s last child when it is a whitespace-only text node (the indent before its close tag). */
private fun trailingWhitespace(parent: Node): Node? =
    parent.lastChild?.takeIf { it.nodeType == Node.TEXT_NODE && it.textContent.isBlank() }

private fun insertBeforeOrAppend(parent: Node, child: Node, ref: Node?) {
    if (ref != null) parent.insertBefore(child, ref) else parent.appendChild(child)
}
