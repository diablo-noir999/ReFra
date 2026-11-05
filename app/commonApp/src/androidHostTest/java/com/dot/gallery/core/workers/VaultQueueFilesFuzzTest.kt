/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.workers

import com.dot.gallery.core.work.VaultQueueFiles
import com.dot.gallery.data.thumbnail.VaultThumbnailStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Fuzzes the disk-backed vault selection queue.
 *
 * The queue replaced the worker input because WorkManager `Data` is capped at 10,240 bytes
 * (#1240). A corrupted line would silently drop or alias a media item during a bulk import, so
 * the escaping has to survive arbitrary provider URIs (and be cheap at tens of thousands of
 * entries).
 */
class VaultQueueFilesFuzzTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTripsArbitraryStrings() {
        val random = Random(0xC0FFEE)
        val alphabet = buildList {
            addAll(listOf("a", "Z", "0", "/", ":", "?", "&", "=", "%", "#", " ",
                "\n", "\r", "\t", "\\", "\"", "'", "é", "ж", "漢", "😀", "\u0000"))
        }
        repeat(5_000) {
            val value = buildString {
                repeat(random.nextInt(0, 40)) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            assertEquals(value, VaultQueueFiles.unescape(VaultQueueFiles.escape(value)))
        }
    }

    @Test
    fun writesAndReadsExactly() {
        val values = listOf(
            "content://media/external/images/media/1",
            "content://media/external/video/media/2?foo=bar&baz=qux",
            "file:///data/user/0/com.dot.gallery/files/uuid/3.enc",
            "content://com.android.providers.media.documents/document/image%3A4",
            "with\\backslash",
            "with\nnewline",
            "with\r\ncrlf",
            "unicode-é-ж-漢-😀",
            "trailing\\",
            ""
        )
        val file = File(tmp.root, "queue.queue")
        VaultQueueFiles.write(file, values)
        // Blank lines are ignored by design, so an empty value is dropped on read.
        assertEquals(values.filter { it.isNotEmpty() }, VaultQueueFiles.read(file))
    }

    @Test
    fun missingFileReadsAsEmpty() {
        assertTrue(VaultQueueFiles.read(File(tmp.root, "nope.queue")).isEmpty())
    }

    @Test
    fun handlesLargeSelectionWithoutStreamLimit() {
        val count = 50_000
        val values = (0 until count).map { "content://media/external/images/media/$it" }
        val file = File(tmp.root, "big.queue")
        VaultQueueFiles.write(file, values)
        assertTrue("queue unexpectedly tiny: ${file.length()}", file.length() > 10_240)
        assertEquals(values, VaultQueueFiles.read(file))
    }

    @Test
    fun leftoversPathMatchesQueue() {
        val queue = VaultQueueFiles.queueFile(tmp.root, "VaultOp_abc")
        val leftovers = VaultQueueFiles.leftoverForQueue(queue)
        assertEquals(queue.parentFile, leftovers.parentFile)
        assertNotEquals(queue, leftovers)
        assertTrue(leftovers.name.startsWith("VaultOp_abc"))
    }

    @Test
    fun purgeStaleOnlyRemovesOldFiles() {
        val dir = tmp.root
        val old = File(dir, "old.queue").apply { writeText("x") }
        val fresh = File(dir, "fresh.queue").apply { writeText("x") }
        old.setLastModified(1_000L)
        VaultQueueFiles.purgeStale(dir, maxAgeMs = 1_000L, now = 10_000L)
        assertTrue(!old.exists())
        assertTrue(fresh.exists())
    }
}

/** Pure math behind vault thumbnails; runs on the host without Android. */
class VaultThumbnailMathTest {

    @Test
    fun sampleSizeKeepsResultAtOrAboveTarget() {
        // 4000px -> 512 target: halving twice is 1000 (>=512), a third time would be 500 (<512).
        assertEquals(4, VaultThumbnailStore.sampleSize(4000, 3000, 512))
        assertEquals(1, VaultThumbnailStore.sampleSize(512, 512, 512))
        assertEquals(1, VaultThumbnailStore.sampleSize(400, 200, 512))
        assertEquals(8, VaultThumbnailStore.sampleSize(5000, 5000, 512))
    }

    @Test
    fun sampleSizeHandlesDegenerateInput() {
        assertEquals(1, VaultThumbnailStore.sampleSize(0, 0, 512))
        assertEquals(1, VaultThumbnailStore.sampleSize(-5, 10, 512))
        assertEquals(1, VaultThumbnailStore.sampleSize(100, 100, 0))
    }
}
