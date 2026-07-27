package com.getlokalapp.paymentsdk.shared

import java.io.File

/** Writes [file] only when its content differs; returns whether it wrote. */
internal fun writeIfChanged(file: File, content: String): Boolean {
    if (!file.exists() || file.readText() != content) {
        file.writeText(content)
        return true
    }
    return false
}
