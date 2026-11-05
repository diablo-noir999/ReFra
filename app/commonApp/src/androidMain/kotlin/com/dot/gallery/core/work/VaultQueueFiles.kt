/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.work

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Disk-backed queue for vault operations.
 *
 * WorkManager/kmpworkmanager stores a worker's input as serialized `Data`, which is capped at
 * 10,240 bytes. Encoding the selection as a JSON array of URIs hits that cap at roughly 180
 * MediaStore URIs (about 120 document-provider URIs), so a bulk "add to vault" crashed before
 * encrypting anything (issue #1240).
 *
 * The fix is to keep the selection out of the worker input entirely: the enqueue helper writes
 * the URI list to a private cache file and passes only its path. The cap then no longer depends
 * on the number of selected files.
 *
 * Format: one URI per line, UTF-8, with backslash escaping so a URI can never split a record.
 * Deliberately dependency-free (no JSON, no Android types) so it can be fuzzed as a plain JVM
 * unit test and so the format is trivial to inspect or recover.
 */
object VaultQueueFiles {

    const val DIRECTORY_NAME = "vault_queue"
    private const val QUEUE_SUFFIX = ".queue"
    private val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(2)

    /** Directory that holds queue and leftover files. */
    fun directory(cacheDir: File): File =
        File(cacheDir, DIRECTORY_NAME)

    /** A fresh queue path for [taskId]. The task id is already unique per enqueue. */
    fun queueFile(directory: File, taskId: String): File =
        File(directory, "$taskId$QUEUE_SUFFIX")

    /** A fresh output path for the URIs a worker could not finish (leftovers). */
    fun leftoverFile(directory: File, taskId: String): File =
        File(directory, "$taskId.leftovers$QUEUE_SUFFIX")

    /**
     * Leftover path matching a queue file, so a worker can derive its output location from the
     * single path it received without knowing the enqueue's task id.
     */
    fun leftoverForQueue(queueFile: File): File =
        File(queueFile.parentFile, "${queueFile.nameWithoutExtension}.leftovers$QUEUE_SUFFIX")

    /**
     * Persist [uris] to [file], replacing any previous content.
     *
     * The list is streamed line by line, so memory use stays flat even for tens of thousands of
     * entries (the caller already holds the list, this avoids a second full serialized copy).
     */
    @Throws(IOException::class)
    fun write(file: File, uris: List<String>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            for (uri in uris) {
                writer.write(escape(uri))
                writer.newLine()
            }
        }
    }

    /**
     * Read a queue file written by [write]. Blank lines are ignored; a missing file reads as
     * empty so callers can treat "already consumed" as "nothing to do".
     */
    @Throws(IOException::class)
    fun read(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<String>()
        file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                out.add(unescape(line))
            }
        }
        return out
    }

    /**
     * Best-effort removal of queue/leftover files older than [maxAgeMs]. A worker killed by the
     * system leaves its input behind; WorkManager will not necessarily run it again, so old
     * files must not accumulate forever.
     */
    fun purgeStale(directory: File, maxAgeMs: Long = STALE_AFTER_MS, now: Long = System.currentTimeMillis()) {
        val files = directory.listFiles() ?: return
        val cutoff = now - maxAgeMs
        for (file in files) {
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    internal fun escape(value: String): String {
        if (value.indexOf('\\') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value
        }
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(ch); i++ }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
