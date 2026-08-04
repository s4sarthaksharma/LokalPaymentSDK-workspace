package com.getlokalapp.paymentsdk.shared

import com.getlokalapp.paymentsdk.host.LokalPaymentSdkExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PRE_ACTION_TITLE = "Lokal Payment SDK prebuild"
private const val SHELL_ACTION_TYPE =
    "Xcode.IDEStandardExecutionActionsCore.ExecutionActionType.ShellScriptAction"

/** One level of Xcode's scheme indentation. */
private const val INDENT_UNIT = "   "

/** Where a shared scheme lives inside its `.xcodeproj` / `.xcworkspace` container. */
private const val SHARED_SCHEMES_PATH = "xcshareddata/xcschemes"

/**
 * The `<BuildAction …> … </BuildAction>` span. Every edit is scoped to it so a `<PreActions>`
 * belonging to the test or launch action is never mistaken for the build one. `[^>]*` is safe
 * for the opening tag because XML forbids a raw `>` inside an attribute value.
 */
private val BUILD_ACTION = Regex("""<BuildAction\b[^>]*>(.*?)</BuildAction>""", RegexOption.DOT_MATCHES_ALL)

/** The whitespace run (with its newline) opening a block's body — the indent of its first child. */
private val LEADING_INDENT = Regex("""^\r?\n[ \t]*""")

/**
 * The attributes of a `<BuildableReference>`, in the order Xcode writes them. Emitting them in
 * this order rather than the source document's is what keeps a written action byte-identical to
 * a hand-added one; anything unrecognised is appended rather than dropped.
 */
private val BUILDABLE_REFERENCE_ATTRIBUTES = listOf(
    "BuildableIdentifier",
    "BlueprintIdentifier",
    "BuildableName",
    "BlueprintName",
    "ReferencedContainer",
)

/**
 * What [patchXcodeSchemesIfConfigured] left behind: the [schemes] carrying the pre-action after
 * this run (empty when nothing is SDK-managed), and whether discovery ran but came up empty —
 * the one outcome that needs its own wording in `INTEGRATION.md`, since the host asked for the
 * automation implicitly (by setting `iosXcodeProject`) and has to be told why it didn't happen.
 */
internal class SchemeWiring(
    val schemes: List<File>,
    val discoveryFoundNoSchemes: Boolean = false,
) {
    val wired: Boolean get() = schemes.isNotEmpty()
}

/**
 * Registers [prebuildScript] as a build pre-action in the host's shared `.xcscheme` files, per
 * the four-way contract on [LokalPaymentSdkExtension.iosXcodeSchemes]: an explicit list is
 * patched verbatim, an unset list with `iosXcodeProject` set discovers that project's shared
 * app schemes, an unset list without it patches nothing (manual steps land in `INTEGRATION.md`
 * instead), and an explicitly empty list opts out.
 *
 * The `.xcscheme` sibling of [patchInfoPlistIfConfigured] and [patchXcodeProjectIfConfigured]:
 * idempotent edits of git-tracked files the host owns. Fails loudly on a listed path that isn't
 * a readable shared `.xcscheme` under a project or workspace bundle, mirroring their fail-loud
 * contract — an explicit opt-in pointing somewhere wrong must not silently do nothing. Discovery
 * is the lenient half of the same coin: it warns rather than fails, because a project whose
 * schemes all sit in `xcuserdata` is a legitimate layout the host didn't opt into.
 */
internal fun patchXcodeSchemesIfConfigured(
    project: Project,
    config: LokalPaymentSdkExtension,
    prebuildScript: File?,
): SchemeWiring {
    val configured = config.iosXcodeSchemes

    // Explicit opt-out. Logged rather than silent: this is the one path where the host has asked
    // for a load-bearing step to be left undone, and a later "my Kotlin edit didn't take" is much
    // easier to explain with this line in the build log.
    if (configured != null && configured.isEmpty()) {
        project.logger.lifecycle(
            "LokalPaymentSDK: lokalPaymentSdk { iosXcodeSchemes } is empty — leaving every " +
                "scheme alone. Register the prebuild pre-action by hand (see INTEGRATION.md), " +
                "or Xcode will build against whichever Kotlin binary is currently staged.",
        )
        return SchemeWiring(emptyList())
    }

    val schemes = when {
        configured != null -> configured.map { validateConfiguredScheme(project, it) }
        config.iosXcodeProject != null -> discoverAppSchemes(project, config)
        // Neither set: the host wires its Xcode files itself (XcodeGen/Tuist own generated
        // ones) — the same do-nothing contract as the plist and pbxproj opt-ins.
        else -> return SchemeWiring(emptyList())
    }

    if (configured == null && schemes.isEmpty()) {
        val schemesDir = File(resolvePbxproj(project, config.iosXcodeProject!!).parentFile, SHARED_SCHEMES_PATH)
        project.logger.warn(
            "LokalPaymentSDK: found no shared scheme building the app target under $schemesDir, " +
                "so the prebuild pre-action was not registered — Xcode will build against " +
                "whichever Kotlin binary is currently staged. Share your app scheme (Product ▸ " +
                "Scheme ▸ Manage Schemes ▸ tick Shared) and re-sync, or set " +
                "lokalPaymentSdk { iosXcodeSchemes = listOf(\"<path-to>.xcscheme\") }.",
        )
        return SchemeWiring(emptyList(), discoveryFoundNoSchemes = true)
    }

    // No dispatcher was written (no gateway contributed a step and the SDK's own staging step
    // couldn't be built) — nothing to register. Listed paths are still validated above, so a
    // wrong path is reported on the sync that introduced it rather than on some later one.
    if (prebuildScript == null) return SchemeWiring(emptyList())

    schemes.forEach { schemeFile ->
        if (addPreAction(schemeFile, prebuildScript)) {
            project.logger.lifecycle(
                "LokalPaymentSDK: registered the prebuild pre-action in $schemeFile",
            )
        }
    }
    return SchemeWiring(schemes)
}

/**
 * One host-listed scheme path, resolved and checked the way the plist and pbxproj opt-ins check
 * theirs. A scheme Xcode left in `xcuserdata` isn't committed, so a pre-action added there would
 * never reach another developer — exactly the silent half-setup this opt-in exists to prevent.
 */
private fun validateConfiguredScheme(project: Project, path: String): File {
    val schemeFile = project.file(path)
    if (!schemeFile.isFile || schemeFile.extension != "xcscheme") {
        throw GradleException(
            "lokalPaymentSdk { iosXcodeSchemes } lists \"$path\", which does not resolve to an " +
                ".xcscheme file (looked at $schemeFile). List the shared schemes your app uses " +
                "— <YourApp>.xcodeproj/$SHARED_SCHEMES_PATH/<Scheme>.xcscheme — or drop the " +
                "property to have the SDK discover them from iosXcodeProject.",
        )
    }
    if (schemeFile.parentFile?.parentFile?.name != "xcshareddata") {
        throw GradleException(
            "lokalPaymentSdk { iosXcodeSchemes } lists \"$path\", which is not a shared scheme " +
                "(expected it under $SHARED_SCHEMES_PATH/, found $schemeFile). Tick \"Shared\" " +
                "for the scheme in Xcode ▸ Product ▸ Scheme ▸ Manage Schemes so it gets " +
                "committed, then list that copy.",
        )
    }
    return schemeFile
}

/**
 * Every shared scheme of the `iosXcodeProject` that builds its application target, sorted by
 * name so the log and `INTEGRATION.md` read the same on every machine.
 *
 * Filtering matters because a discovered set is whatever the host happens to have shared: a
 * framework-only, test-only or app-extension scheme would otherwise get a pre-action that
 * restages the Kotlin binary using a `$CONFIGURATION` from the wrong target. Listed schemes are
 * deliberately *not* filtered — the host named them, and fail-loud beats second-guessing.
 *
 * Scoped to the project bundle's own `xcshareddata`; a `.xcworkspace` is never scanned (see
 * [LokalPaymentSdkExtension.iosXcodeSchemes]).
 */
private fun discoverAppSchemes(project: Project, config: LokalPaymentSdkExtension): List<File> {
    val pbxproj = resolvePbxproj(project, config.iosXcodeProject!!)
    val schemesDir = File(pbxproj.parentFile, SHARED_SCHEMES_PATH)
    val schemes = schemesDir.listFiles()
        ?.filter { it.isFile && it.extension == "xcscheme" }
        ?.sortedBy { it.name }
        ?: return emptyList()
    val appTargets = applicationTargetIdentifiers(pbxproj)
    return schemes.filter { buildsAppTarget(it, appTargets) }
}

/**
 * Whether [schemeFile]'s build action builds one of [appTargets], matched on either identifier a
 * scheme names a target by: `BlueprintIdentifier` (the pbxproj object id) or `BlueprintName`.
 *
 * An empty [appTargets] means the pbxproj couldn't be read for them, and returns true: patching
 * a scheme too many costs a redundant staging check, while patching none reintroduces the silent
 * stale-framework failure the automation exists to prevent.
 */
private fun buildsAppTarget(schemeFile: File, appTargets: Set<String>): Boolean {
    val references = buildEntryReferences(parseScheme(schemeFile))
    if (references.isEmpty()) return false
    if (appTargets.isEmpty()) return true
    return references.any {
        it.getAttribute("BlueprintIdentifier") in appTargets ||
            it.getAttribute("BlueprintName") in appTargets
    }
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
 * `$CONFIGURATION` and friends are in scope. That buildable reference is copied from the
 * scheme's own `<BuildActionEntries>` rather than re-derived from the `pbxproj`: the scheme
 * already carries exactly the identifiers Xcode wants, so there is nothing to look up and no
 * coupling to [LokalPaymentSdkExtension.iosXcodeProject] being set too.
 *
 * **Splices text rather than re-serializing the parsed document.** A scheme is attribute-heavy
 * and Xcode writes one attribute per line in its own order, neither of which survives a DOM
 * round-trip: writing the tree back reflows and alphabetizes every attribute in the file, so a
 * one-action addition lands as a whole-file rewrite. The DOM is therefore used only to *read*
 * (the buildable reference, and whether the action is already there), while the write is a
 * targeted insertion inside `<BuildAction>` — the same surgical contract as
 * [patchXcodeProjectIfConfigured] on pbxproj text, and the reason a re-registration is diff-free
 * against a hand-added action.
 *
 * Idempotency keys off [prebuildScript]'s file name appearing in any existing `scriptText`, not
 * off anything we generated, so an action the host already added by hand is recognised and left
 * exactly as-is rather than duplicated.
 */
private fun addPreAction(schemeFile: File, prebuildScript: File): Boolean {
    val text = schemeFile.readText()
    if (alreadyRegistered(text, prebuildScript)) return false

    val buildAction = BUILD_ACTION.find(text)
        ?: throw GradleException(
            "$schemeFile has no <BuildAction> to add a pre-action to — not a well-formed scheme.",
        )
    val body = buildAction.groupValues[1]
    val bodyStart = buildAction.range.first + buildAction.value.indexOf('>') + 1

    // Indentation is read off the file rather than assumed: Xcode uses 3 spaces, but that is a
    // formatting detail to follow, not to enforce.
    val childIndent = LEADING_INDENT.find(body)?.value ?: "\n${INDENT_UNIT.repeat(2)}"

    // An absolute path would get committed and break on every other clone, so the script is
    // expressed relative to the $SRCROOT the action will actually see.
    val doc = parseScheme(schemeFile)
    val relativeScript = schemeSrcRoot(schemeFile, doc).toPath().toAbsolutePath().normalize()
        .relativize(prebuildScript.toPath().toAbsolutePath().normalize())
        .toString()

    val preActionsOpen = body.indexOf(PRE_ACTIONS_OPEN)
    val actionIndent = when {
        preActionsOpen < 0 -> childIndent + INDENT_UNIT
        else -> LEADING_INDENT
            .find(body.substring(preActionsOpen + PRE_ACTIONS_OPEN.length))?.value
            ?: (childIndent + INDENT_UNIT)
    }
    val action = preActionText(relativeScript, appBuildEntryReference(doc), actionIndent)

    val newBody = if (preActionsOpen < 0) {
        // Xcode's scheme schema orders PreActions → PostActions → BuildActionEntries, so the new
        // block goes ahead of whatever the build action already holds.
        childIndent + PRE_ACTIONS_OPEN + action + childIndent + PRE_ACTIONS_CLOSE + body
    } else {
        // Append as the last action, keeping the host's own pre-actions in their existing order
        // (and running after them). Insert ahead of the indent that precedes </PreActions> so
        // that closing tag keeps its own line.
        val close = body.lastIndexOf(PRE_ACTIONS_CLOSE)
        var at = close
        while (at > 0 && body[at - 1].isWhitespace()) at--
        // A previously empty <PreActions></PreActions> has no indent before its close tag, so
        // add one or </PreActions> would glue onto the action just inserted.
        val closeIndent = if (body.substring(at, close).contains('\n')) "" else childIndent
        body.substring(0, at) + action + closeIndent + body.substring(at)
    }

    schemeFile.writeText(text.substring(0, bodyStart) + newBody + text.substring(bodyStart + body.length))
    return true
}

private const val PRE_ACTIONS_OPEN = "<PreActions>"
private const val PRE_ACTIONS_CLOSE = "</PreActions>"

/**
 * The `<ExecutionAction>` block, formatted the way Xcode formats one: one attribute per line,
 * each nesting level one [INDENT_UNIT] deeper than its parent, and `>` closing the last
 * attribute line of a tag.
 *
 * [reference] is the app's buildable reference to copy into `<EnvironmentBuildable>` — Xcode's
 * "Provide build settings from <app target>". Without it `$CONFIGURATION` is unset, and the
 * Kotlin staging step can then no longer tell a Debug build from a Release one; a scheme with no
 * build entries to copy from gets the action anyway, minus the environment.
 */
private fun preActionText(relativeScript: String, reference: Element?, actionIndent: String): String {
    val contentIndent = actionIndent + INDENT_UNIT
    val environmentIndent = contentIndent + INDENT_UNIT
    val referenceIndent = environmentIndent + INDENT_UNIT
    val attributeIndent = referenceIndent + INDENT_UNIT

    return buildString {
        append(actionIndent).append("<ExecutionAction")
        append(contentIndent).append("ActionType = \"$SHELL_ACTION_TYPE\">")
        append(contentIndent).append("<ActionContent")
        append(environmentIndent).append("title = \"$PRE_ACTION_TITLE\"")
        // No trailing newline in the script text. Xcode writes one as &#10;, but the shell does
        // not need it, and it would only add noise to the attribute.
        append(environmentIndent)
            .append("scriptText = \"${escapeAttribute("\"\$SRCROOT/$relativeScript\"")}\"")
        if (reference == null) {
            append(">")
        } else {
            append(">")
            append(environmentIndent).append("<EnvironmentBuildable>")
            append(referenceIndent).append("<BuildableReference")
            val attributes = referenceAttributeNames(reference)
            attributes.forEachIndexed { index, name ->
                append(attributeIndent)
                    .append("$name = \"${escapeAttribute(reference.getAttribute(name))}\"")
                if (index == attributes.lastIndex) append(">")
            }
            append(referenceIndent).append("</BuildableReference>")
            append(environmentIndent).append("</EnvironmentBuildable>")
        }
        append(contentIndent).append("</ActionContent>")
        append(actionIndent).append("</ExecutionAction>")
    }
}

/** [reference]'s attribute names: Xcode's canonical order first, then anything unrecognised. */
private fun referenceAttributeNames(reference: Element): List<String> {
    val present = reference.attributes
    val extras = (0 until present.length)
        .map { present.item(it).nodeName }
        .filterNot { it in BUILDABLE_REFERENCE_ATTRIBUTES }
    return BUILDABLE_REFERENCE_ATTRIBUTES.filter { reference.getAttribute(it).isNotEmpty() } + extras
}

private fun escapeAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * The `$SRCROOT` a pre-action in [schemeFile] will see: the directory holding the `.xcodeproj`
 * that owns the target the action takes its build settings from — which is *not* always the
 * directory the scheme sits under. A scheme shared at the workspace level lives in
 * `<dir>/App.xcworkspace/$SHARED_SCHEMES_PATH/`, while `$SRCROOT` still points at the project's
 * own directory, so reading it off the scheme's location on disk is only accidentally right when
 * the `.xcodeproj` happens to sit beside the `.xcworkspace`.
 *
 * Taken from the environment target's `ReferencedContainer` instead ("container:iosApp.xcodeproj",
 * resolved against the directory holding the scheme's container bundle), which is correct for
 * both layouts. Falls back to that directory for a scheme carrying no build entries at all,
 * which is the same answer for the project-hosted case.
 */
private fun schemeSrcRoot(schemeFile: File, doc: Document): File {
    // scheme → xcschemes → xcshareddata → <name>.xcodeproj|.xcworkspace → the dir holding it.
    val bundle = schemeFile.parentFile?.parentFile?.parentFile
    val containerRoot = bundle?.parentFile
    if (containerRoot == null || (bundle.extension != "xcodeproj" && bundle.extension != "xcworkspace")) {
        throw GradleException(
            "$schemeFile is not inside an .xcodeproj or .xcworkspace bundle, so the \$SRCROOT " +
                "its pre-action would run with can't be determined. List a scheme under " +
                "<YourApp>.xcodeproj/$SHARED_SCHEMES_PATH/ instead.",
        )
    }
    val container = appBuildEntryReference(doc)?.getAttribute("ReferencedContainer")
        ?.removePrefix("container:")
        ?.takeIf { it.isNotBlank() }
        ?: return containerRoot
    return File(containerRoot, container).parentFile ?: containerRoot
}

/**
 * Whether some `scriptText` already runs [prebuildScript] (ours, or hand-added). Matched on the
 * script's file name over the whole scheme, so an action a developer moved to the test or launch
 * phase still counts as registered rather than being duplicated into the build phase.
 */
private fun alreadyRegistered(text: String, prebuildScript: File): Boolean =
    Regex("""scriptText\s*=\s*"[^"]*${Regex.escape(prebuildScript.name)}""").containsMatchIn(text)

/**
 * The buildable reference under `<BuildActionEntries>` naming the app — the target Xcode would
 * offer as "Provide build settings from", and the one whose `.xcodeproj` defines `$SRCROOT`.
 *
 * Prefers an entry whose `BuildableName` ends in `.app` over document order: discovery can reach
 * a scheme that builds a framework or extension alongside the app, where taking the first entry
 * would hand the pre-action the wrong target's build settings.
 */
private fun appBuildEntryReference(doc: Document): Element? {
    val references = buildEntryReferences(doc)
    return references.firstOrNull { it.getAttribute("BuildableName").endsWith(".app") }
        ?: references.firstOrNull()
}

/**
 * Every `<BuildableReference>` under `<BuildActionEntries>`, in document order. Scoped to the
 * build entries rather than the whole document, since an existing pre-action's own
 * `<EnvironmentBuildable>` appears earlier in document order.
 */
private fun buildEntryReferences(doc: Document): List<Element> {
    val entries = doc.getElementsByTagName("BuildActionEntries").item(0) as? Element
        ?: return emptyList()
    val references = entries.getElementsByTagName("BuildableReference")
    return (0 until references.length).mapNotNull { references.item(it) as? Element }
}

private fun parseScheme(schemeFile: File): Document =
    DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(schemeFile)
