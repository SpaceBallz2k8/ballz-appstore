package com.ballz.appstore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Pulls APKs out of .zip / .tar / .tar.gz / .tgz release assets. No external dependencies. */
object ArchiveExtractor {
    class Entry(val name: String, val file: File)

    /** Returns the APK to install: [downloaded] itself if it's a plain APK, else the APK found inside. */
    suspend fun resolveApk(
        downloaded: File,
        assetName: String,
        innerPattern: String?,
        workDir: File,
    ): File = withContext(Dispatchers.IO) {
        if (!ApkPicker.isArchive(assetName)) return@withContext downloaded
        val entries = extractApks(downloaded, assetName, workDir)
        downloaded.delete()
        val idx = ApkPicker.pickInner(entries.map { it.name }, innerPattern)
            ?: error(
                if (entries.isEmpty()) "No APK found inside $assetName."
                else "No suitable APK inside the archive (found: ${entries.joinToString { it.name }})."
            )
        entries[idx].file
    }

    private fun extractApks(archive: File, assetName: String, outDir: File): List<Entry> {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val n = assetName.lowercase()
        val found = mutableListOf<Entry>()
        archive.inputStream().buffered().use { raw ->
            when {
                n.endsWith(".zip") -> ZipInputStream(raw).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.lowercase().endsWith(".apk")) {
                            val f = File(outDir, "inner${found.size}.apk")
                            f.outputStream().use { z.copyTo(it) }
                            found += Entry(e.name, f)
                        }
                        e = z.nextEntry
                    }
                }
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> readTar(GZIPInputStream(raw), outDir, found)
                else -> readTar(raw, outDir, found)
            }
        }
        return found
    }

    // ---- minimal tar reader (regular files, GNU long names, pax paths) ----

    private fun readTar(input: InputStream, outDir: File, found: MutableList<Entry>) {
        val header = ByteArray(512)
        var pendingName: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return

            var name = cstr(header, 0, 100)
            // POSIX ustar magic is "ustar\0"; GNU tar ("ustar  ") reuses the prefix field, so skip it there.
            if (String(header, 257, 5, Charsets.US_ASCII) == "ustar" && header[262] == 0.toByte()) {
                val prefix = cstr(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }
            val size = cstr(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val padding = (512 - size % 512) % 512
            val type = header[156].toInt().toChar()

            when (type) {
                'L' -> {                       // GNU long name for the next entry
                    pendingName = String(readN(input, size), Charsets.UTF_8).trimEnd('\u0000')
                    skip(input, padding)
                }
                'x' -> {                       // pax extended header; may carry path=
                    val pax = String(readN(input, size), Charsets.UTF_8)
                    pax.lineSequence()
                        .map { it.substringAfter(' ', "") }
                        .firstOrNull { it.startsWith("path=") }
                        ?.let { pendingName = it.removePrefix("path=") }
                    skip(input, padding)
                }
                '0', '\u0000' -> {             // regular file
                    val finalName = pendingName ?: name
                    pendingName = null
                    if (finalName.lowercase().endsWith(".apk")) {
                        val f = File(outDir, "inner${found.size}.apk")
                        f.outputStream().use { out ->
                            var left = size
                            val buf = ByteArray(32 * 1024)
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) error("Archive is truncated.")
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        skip(input, padding)
                        found += Entry(finalName, f)
                    } else skip(input, size + padding)
                }
                else -> {                      // directories, links, global pax headers, etc.
                    pendingName = null
                    skip(input, size + padding)
                }
            }
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) return got != 0 && error("Archive is truncated.")
            got += n
        }
        return true
    }

    private fun readN(input: InputStream, n: Long): ByteArray {
        require(n in 0..1_000_000) { "Unexpected tar header size" }
        val out = ByteArray(n.toInt())
        var got = 0
        while (got < out.size) {
            val r = input.read(out, got, out.size - got)
            if (r < 0) error("Archive is truncated.")
            got += r
        }
        return out
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("Archive is truncated.")
            left -= n
        }
    }
}
