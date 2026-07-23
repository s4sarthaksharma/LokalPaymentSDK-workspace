package com.getlokalapp.paymentsdk.host

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Which of the [wanted] SDK gateway modules (artifactIds under [group]) this project ships —
 * directly, or transitively through a `project()` dependency such as a shared KMP module. Walks
 * the project's declared-dependency graph so the umbrella Android plugin can gate every
 * [LokalGatewayHostAndroidContributor] at once with a membership test, instead of each
 * contributor re-walking the graph. Mirrors the iOS contributors' import gate, without
 * requiring a redundant declaration on the `com.android.application` module.
 *
 * Pass the modules that actually have a contributor (the umbrella's `contributors.keys`): the
 * walk targets only those and **short-circuits as soon as it has found all of them**, so in a
 * large multi-module product it stops descending once the shipped gateways are located rather
 * than visiting the whole reachable project graph. A gateway's *absence* has no early exit,
 * though — confirming a module isn't shipped still requires walking the full reachable graph
 * (cheap: a linear, `visited`-guarded traversal of declared deps).
 *
 * Walks *declared* dependencies only, so it never resolves a runtime classpath — AGP
 * disallows resolving those at configuration time (it locks the configuration before a
 * vendor plugin like `hypersdk.plugin` can inject its dependencies). Following a
 * `project()` edge uses [Project.evaluationDependsOn] rather than a plain
 * `project(path)` lookup, because the dependency project may not be configured yet when
 * this runs (its `configurations` would appear empty depending on evaluation order);
 * `evaluationDependsOn` forces it to configure first. Self/already-seen paths are skipped
 * before that call, since `evaluationDependsOn` on the current project's own path throws
 * "circular".
 *
 * Call this from inside a `plugins.withId("com.android.application") { afterEvaluate { … } }`
 * block: the `afterEvaluate` is needed because a module's `dependencies { }` is evaluated
 * after its `plugins { }`, so declarations aren't visible at apply time.
 *
 * NOTE: cross-project reads like this are not configuration-cache compatible; the SDK
 * builds with the configuration cache off.
 */
fun Project.transitiveSdkModules(group: String, wanted: Set<String>): Set<String> {
    val visited = mutableSetOf<String>()
    val found = mutableSetOf<String>()
    fun visit(project: Project) {
        // Stop descending once every wanted module is found (empty `wanted` → true immediately,
        // so nothing is walked), or when this project has already been visited.
        if (found.containsAll(wanted) || !visited.add(project.path)) return
        project.configurations.forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                when (dependency) {
                    is ProjectDependency ->
                        if (dependency.path !in visited) visit(evaluationDependsOn(dependency.path))
                    else -> if (dependency.group == group && dependency.name in wanted) found += dependency.name
                }
            }
        }
    }
    visit(this)
    return found
}
