package com.getlokalapp.paymentsdk.host

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * True when this project ships the module [group]:[name] — directly, or transitively
 * through a `project()` dependency such as a shared KMP module. The self-gate a
 * [LokalGatewayHostAndroidContributor] uses to wire its vendor SDK only when the host
 * actually depends on the gateway (mirroring the iOS contributors' import gate), without
 * requiring a redundant declaration on the `com.android.application` module.
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
fun Project.transitivelyDependsOn(group: String, name: String): Boolean {
    val visited = mutableSetOf<String>()
    fun visit(project: Project): Boolean {
        if (!visited.add(project.path)) return false
        return project.configurations.any { configuration ->
            configuration.dependencies.any { dependency ->
                when (dependency) {
                    is ProjectDependency ->
                        dependency.path !in visited && visit(evaluationDependsOn(dependency.path))
                    else -> dependency.group == group && dependency.name == name
                }
            }
        }
    }
    return visit(this)
}
