package com.getlokalapp.paymentsdk.buildsrc

import java.io.File
import java.util.zip.ZipFile

/**
 * Downloads and installs one directory from a vendor tar.gz archive.
 * Download failure and unsafe archive paths fail the build; artifact integrity
 * is intentionally delegated to the pinned vendor version and HTTPS endpoint.
 */
fun installTarGzDirectory(
    url: String,
    archiveFile: File,
    extractionRoot: File,
    archiveDirectory: String,
    destination: File,
) {
    download(url, archiveFile)
    val entries = runAndCapture("tar", "tzf", archiveFile.absolutePath)
    validateEntries(entries, "$archiveDirectory/")
    runCommand(
        "tar", "xzf", archiveFile.absolutePath,
        "-C", extractionRoot.absolutePath,
        archiveDirectory,
    )
    installExtractedDirectory(extractionRoot.resolve(archiveDirectory), destination)
}

/** Downloads and installs one top-level directory from a vendor zip archive. */
fun installZipDirectory(
    url: String,
    archiveFile: File,
    extractionRoot: File,
    directoryName: String,
    destination: File,
) {
    download(url, archiveFile)
    val entries = ZipFile(archiveFile).use { archive ->
        archive.entries().asSequence().map { it.name }.toList()
    }
    validateEntries(entries, "$directoryName/")
    runCommand(
        "unzip", "-q", "-o", archiveFile.absolutePath,
        "$directoryName/*", "-d", extractionRoot.absolutePath,
    )
    installExtractedDirectory(extractionRoot.resolve(directoryName), destination)
}

private fun download(url: String, archiveFile: File) {
    archiveFile.parentFile.mkdirs()
    runCommand("curl", "-fL", "--retry", "2", "-o", archiveFile.absolutePath, url)
}

private fun validateEntries(entries: List<String>, expectedPrefix: String) {
    check(entries.any { it.startsWith(expectedPrefix) }) {
        "Vendor archive does not contain expected directory $expectedPrefix"
    }
    check(entries.none(::isUnsafeArchivePath)) {
        "Vendor archive contains an unsafe path"
    }
}

private fun isUnsafeArchivePath(rawPath: String): Boolean {
    val path = rawPath.replace('\\', '/')
    return path.startsWith('/') ||
        Regex("^[A-Za-z]:/").containsMatchIn(path) ||
        path.split('/').any { it == ".." }
}

private fun installExtractedDirectory(extracted: File, destination: File) {
    check(extracted.isDirectory) { "Expected extracted directory is missing: ${extracted.name}" }
    destination.deleteRecursively()
    destination.parentFile.mkdirs()
    check(extracted.copyRecursively(destination, overwrite = true)) {
        "Could not install vendor directory ${destination.name}"
    }
}

private fun runAndCapture(vararg command: String): List<String> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readLines()
    check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
    return output
}

private fun runCommand(vararg command: String) {
    val process = ProcessBuilder(*command).inheritIO().start()
    check(process.waitFor() == 0) { "Command failed: ${command.joinToString(" ")}" }
}
