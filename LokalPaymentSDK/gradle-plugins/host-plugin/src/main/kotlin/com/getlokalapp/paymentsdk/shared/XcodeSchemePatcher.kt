package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val PRE_ACTION_TITLE = "Lokal Payment SDK prebuild"
private const val SHELL_ACTION_TYPE =
    "Xcode.IDEStandardExecutionActionsCore.ExecutionActionType.ShellScriptAction"

/** One level of Xcode's scheme indentation. */
private const val INDENT_UNIT = "   "

/**
 * Registers [prebuildScript] as a build pre-action in the host's shared `.xcscheme` when the host
 * opted in via `lokalPaymentSdk { iosXcodeScheme = … }`; returns whether the scheme is SDK-managed
 * (i.e. the property was set), regardless of whether this run actually changed anything. Unset →
 * returns false and the scheme is left untouched, the manual "Edit Scheme ▸ Pre-actions" steps
 * surfaced as an `INTEGRATION.md` note instead (see [writeIntegrationNotes]).
 *
 * The `.xcscheme` sibling of [patchInfoPlistIfConfigured] and [patchXcodeProjectIfConfigured]: an
 * opt-in, idempotent edit of a git-tracked file the host owns. Fails loudly if the path isn't a
 * readable `.xcscheme` under an `.xcodeproj`'s `xcshareddata`, mirroring their fail-loud contract —
 * an explicit opt-in pointing somewhere wrong must not silently do nothing.
 */
internal fun patchXcodeSchemeIfConfigured(
    project: Project,
    config: LokalPaymentSdkExtension,
    prebuildScript: File?,
): Boolean {
    val path = config.iosXcodeScheme ?: return false
    val schemeFile = project.file(path)
    if (!schemeFile.isFile || schemeFile.extension != "xcscheme") {
        throw GradleException(
            "lokalPaymentSdk { iosXcodeScheme = \"$path\" } does not resolve to an .xcscheme " +
                "file (looked at $schemeFile). Point it at the shared scheme your app uses — " +
                "<YourApp>.xcodeproj/xcshareddata/xcschemes/<Scheme>.xcscheme — or remove the " +
                "property to register the pre-build action by hand.",
        )
    }
    // A scheme Xcode left in xcuserdata isn't committed, so a pre-action added there would never
    // reach another developer — exactly the silent half-setup this opt-in exists to prevent.
    if (schemeFile.parentFile?.parentFile?.name != "xcshareddata") {
        throw GradleException(
            "lokalPaymentSdk { iosXcodeScheme = \"$path\" } is not a shared scheme (expected it " +
                "under xcshareddata/xcschemes/, found $schemeFile). Tick \"Shared\" for the " +
                "scheme in Xcode ▸ Product ▸ Scheme ▸ Manage Schemes so it gets committed, then " +
                "point this at that copy.",
        )
    }
    // No dispatcher was written (no gateway contributed a step and the SDK's own staging step
    // couldn't be built) — nothing to register, but the scheme is still SDK-managed.
    if (prebuildScript == null) return true

    if (addPreAction(schemeFile, prebuildScript)) {
        project.logger.lifecycle(
            "LokalPaymentSDK: registered the prebuild pre-action in $schemeFile",
        )
    }
    return true
}

/**
 * Idempotently adds a shell-script pre-action running [prebuildScript] to [schemeFile]'s
 * `<BuildAction>`, creating `<PreActions>` if absent. Returns whether it wrote (false → already
 * registered, file left byte-for-byte untouched — the same "if it is there don't do anything"
 * contract as [patchXcodeProjectIfConfigured]).
 *
 * Faithful to what Xcode writes when a Run Script pre-action is added by hand: an
 * `<ExecutionAction>` of [SHELL_ACTION_TYPE] wrapping an `<ActionContent>` whose `scriptText`
 * invokes the script, plus an `<EnvironmentBuildable>` naming the app target so `$SRCROOT`,
 * `$CONFIGURATION` and friends are in scope. That buildable reference is **cloned from the
 * scheme's own `<BuildActionEntries>`** rather than re-derived from the `pbxproj`: the scheme
 * already carries exactly the identifiers Xcode wants, so there is nothing to look up and no
 * coupling to [LokalPaymentSdkExtension.iosXcodeProject] being set too.
 *
 * Preserves the file's formatting the same way [mergeQueriesSchemes] does — parses with whitespace
 * retained, inserts only new nodes with matching indentation, writes back without re-indenting —
 * so a plain sync is a zero-line diff.
 *
 * Idempotency keys off [prebuildScript]'s file name appearing in any existing `scriptText`, not off
 * anything we generated, so an action the host already added by hand is recognised and left exactly
 * as-is rather than duplicated.
 */
private fun addPreAction(schemeFile: File, prebuildScript: File): Boolean {
    val doc = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(schemeFile)

    val buildAction = elementChildren(doc.documentElement)
        .firstOrNull { it.tagName == "BuildAction" }
        ?: throw GradleException(
            "$schemeFile has no <BuildAction> to add a pre-action to — not a well-formed scheme.",
        )

    if (alreadyRegistered(doc, prebuildScript)) return false

    // $SRCROOT in a scheme pre-action is the directory CONTAINING the .xcodeproj, so express the
    // script relative to it — an absolute path gets committed and breaks on every other clone.
    // scheme file → xcschemes → xcshareddata → <name>.xcodeproj → the directory holding it.
    val projectSrcRoot = schemeFile.parentFile?.parentFile?.parentFile?.parentFile
        ?: throw GradleException("$schemeFile is not inside an .xcodeproj bundle.")
    val relativeScript = projectSrcRoot.toPath().toAbsolutePath().normalize()
        .relativize(prebuildScript.toPath().toAbsolutePath().normalize())
        .toString()

    // Indentation is read off the file rather than assumed: Xcode uses 3 spaces, but that is a
    // formatting detail to follow, not to enforce.
    val childIndent = leadingWhitespace(buildAction) ?: "\n${INDENT_UNIT.repeat(2)}"
    val existingPreActions = elementChildren(buildAction).firstOrNull { it.tagName == "PreActions" }
    val preActions = existingPreActions ?: createPreActions(doc, buildAction, childIndent)
    val actionIndent = leadingWhitespace(preActions) ?: (childIndent + INDENT_UNIT)

    val action = buildPreAction(doc, relativeScript, actionIndent)

    val tail = trailingWhitespace(preActions)
    insertBeforeOrAppend(preActions, doc.createTextNode(actionIndent), tail)
    insertBeforeOrAppend(preActions, action, tail)
    // A freshly-created (previously empty) PreActions has no closing-indent text node, so add one
    // or </PreActions> would glue onto the action just appended.
    if (tail == null) preActions.appendChild(doc.createTextNode(childIndent))

    writeScheme(doc, schemeFile)
    return true
}

/** Whether some `<ActionContent>` already runs [prebuildScript] (ours, or hand-added). */
private fun alreadyRegistered(doc: Document, prebuildScript: File): Boolean {
    val contents = doc.getElementsByTagName("ActionContent")
    return (0 until contents.length).any {
        (contents.item(it) as? Element)?.getAttribute("scriptText")?.contains(prebuildScript.name) == true
    }
}

private fun buildPreAction(doc: Document, relativeScript: String, actionIndent: String): Element {
    val contentIndent = actionIndent + INDENT_UNIT
    val environmentIndent = contentIndent + INDENT_UNIT
    val referenceIndent = environmentIndent + INDENT_UNIT

    val content = doc.createElement("ActionContent").apply {
        setAttribute("title", PRE_ACTION_TITLE)
        // No trailing newline. Xcode writes one as &#10;, but a literal newline in an attribute is
        // normalised to a space by any conforming parser, so emitting one would silently mangle
        // the script. The shell does not need it.
        setAttribute("scriptText", "\"\$SRCROOT/$relativeScript\"")
    }
    // "Provide build settings from <app target>". Without it $CONFIGURATION is unset, and the
    // Kotlin staging step can then no longer tell a Debug build from a Release one.
    appTargetReference(doc)?.let { reference ->
        val environment = doc.createElement("EnvironmentBuildable").apply {
            appendChild(doc.createTextNode(referenceIndent))
            appendChild(reference)
            appendChild(doc.createTextNode(environmentIndent))
        }
        content.appendChild(doc.createTextNode(environmentIndent))
        content.appendChild(environment)
        content.appendChild(doc.createTextNode(contentIndent))
    }
    return doc.createElement("ExecutionAction").apply {
        setAttribute("ActionType", SHELL_ACTION_TYPE)
        appendChild(doc.createTextNode(contentIndent))
        appendChild(content)
        appendChild(doc.createTextNode(actionIndent))
    }
}

/**
 * Creates `<PreActions>` as [buildAction]'s first child. Xcode's scheme schema orders
 * `PreActions` → `PostActions` → `BuildActionEntries`, so this inserts ahead of whatever is
 * already there rather than appending after it.
 */
private fun createPreActions(doc: Document, buildAction: Element, childIndent: String): Element {
    val preActions = doc.createElement("PreActions")
    val firstChild = buildAction.firstChild
    if (firstChild != null) {
        buildAction.insertBefore(doc.createTextNode(childIndent), firstChild)
        buildAction.insertBefore(preActions, firstChild)
    } else {
        buildAction.appendChild(doc.createTextNode(childIndent))
        buildAction.appendChild(preActions)
    }
    return preActions
}

/**
 * A shallow copy of the buildable reference under `<BuildActionEntries>` — the app target Xcode
 * would offer as "Provide build settings from". Scoped to the build entries rather than the first
 * `<BuildableReference>` in the document, since an existing pre-action's own
 * `<EnvironmentBuildable>` appears earlier in document order.
 *
 * Null when the scheme has no build entries at all; the pre-action is still written, just without
 * an environment, and the staging step warns at build time that `CONFIGURATION` is unset.
 */
private fun appTargetReference(doc: Document): Element? {
    val entries = doc.getElementsByTagName("BuildActionEntries").item(0) ?: return null
    val references = (entries as? Element)?.getElementsByTagName("BuildableReference") ?: return null
    val first = (0 until references.length)
        .firstNotNullOfOrNull { references.item(it) as? Element }
        ?: return null
    // Shallow: BuildableReference carries only attributes, and it already belongs to this document.
    return first.cloneNode(false) as Element
}

private fun writeScheme(doc: Document, file: File) {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        // We manage whitespace by hand (above), so leave re-indenting off for a minimal diff.
        setOutputProperty(OutputKeys.INDENT, "no")
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    // Match how Xcode writes the prolog: no `standalone` attribute, <Scheme> on its own line.
    var out = writer.toString()
        .replace(" standalone=\"no\"?>", "?>")
        .replace("?><Scheme", "?>\n<Scheme")
    if (!out.endsWith("\n")) out += "\n"
    file.writeText(out)
}
