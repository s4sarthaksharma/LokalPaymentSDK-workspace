package com.getlokalapp.paymentsdk.cocoapods

import java.io.File

/**
 * Shared podspec-editing logic for the per-gateway `*-cocoapods-host-plugin`
 * modules. This source file is compiled into each plugin via a `srcDir` entry
 * (see their `build.gradle.kts`) rather than a published artifact, so every
 * plugin jar stays self-contained.
 */
internal object PodspecEditor {

    /**
     * Ensures the generated `podspec` declares `spec.dependency '<pod>', '<version>'`.
     *
     * An existing declaration for [pod] — with any version or none — is rewritten
     * to the pinned [version] rather than kept, so it can't drift from the
     * cinterop bindings the host consumes. If the pod is absent, the line is
     * appended before the podspec's closing `end`. No write happens when the file
     * already matches.
     */
    fun upsertPodDependency(podspec: File, pod: String, version: String) {
        if (!podspec.exists()) return
        val text = podspec.readText()
        val line = "    spec.dependency '$pod', '$version'"
        // Match an existing declaration for this pod, with or without a version,
        // so a stale/different version gets rewritten to our pin rather than kept.
        val existing = Regex(
            "^[ \\t]*spec\\.dependency '${Regex.escape(pod)}'.*$",
            RegexOption.MULTILINE,
        )
        val updated = if (existing.containsMatchIn(text)) {
            existing.replace(text, Regex.escapeReplacement(line))
        } else {
            // Insert before the podspec's closing `end`.
            val at = text.lastIndexOf("\nend")
            if (at < 0) return
            text.substring(0, at) + "\n" + line + text.substring(at)
        }
        if (updated != text) podspec.writeText(updated)
    }
}
