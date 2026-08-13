package com.getlokalapp.paymentsdk.buildsrc

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Downloads and installs one directory from a trusted tar.gz archive.
 *
 * Intended for vendor binaries fetched by custom Gradle tasks, which are not
 * visible to Gradle's dependency-verification metadata.
 */
fun installVerifiedTarGzDirectory(
    url: String,
    expectedSha256: String,
    archiveFile: File,
    extractionRoot: File,
    archiveDirectory: String,
    destination: File,
) {
    downloadAndVerify(url, expectedSha256, archiveFile)
    val entries = runAndCapture("tar", "tzf", archiveFile.absolutePath)
    validateEntries(entries, "$archiveDirectory/")
    runCommand(
        "tar", "xzf", archiveFile.absolutePath,
        "-C", extractionRoot.absolutePath,
        archiveDirectory,
    )
    installExtractedDirectory(extractionRoot.resolve(archiveDirectory), destination)
}

/** Downloads and installs one top-level directory from a trusted zip archive. */
fun installVerifiedZipDirectory(
    url: String,
    expectedSha256: String,
    archiveFile: File,
    extractionRoot: File,
    directoryName: String,
    destination: File,
) {
    downloadAndVerify(url, expectedSha256, archiveFile)
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

private fun downloadAndVerify(url: String, expectedSha256: String, archiveFile: File) {
    require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
        "Expected SHA-256 must contain exactly 64 hexadecimal characters"
    }
    archiveFile.parentFile.mkdirs()
    runCommand("curl", "-fL", "--retry", "2", "-o", archiveFile.absolutePath, url)

    val expected = expectedSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val actual = archiveFile.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest()
    }
    check(MessageDigest.isEqual(expected, actual)) {
        val actualHex = actual.joinToString("") { "%02x".format(it) }
        "Vendor archive SHA-256 mismatch: expected=$expectedSha256 actual=$actualHex"
    }
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
        "Could not install verified vendor directory ${destination.name}"
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
