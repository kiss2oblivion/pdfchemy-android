package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Representation-level comparison of effective page resource dictionaries.
 * Includes nested fonts, XObjects, color spaces, filters and other reachable
 * resource objects. Indirect object numbers and dictionary insertion order
 * are ignored. Encoded streams are never decompressed. A cycle, unsupported
 * value, read failure, or exhausted budget makes the affected sample unknown.
 * This is evidence, not a resource-validity or rendering certificate.
 */
internal object PageResourceEvidence {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_DEPTH = 64
    private const val MAX_VISITS = SecurityLimits.MAX_GRAPH_NODES
    private const val TIME_NS = 5_000_000_000L
    private class Unmeasured : IOException("Resource evidence unavailable")

    fun capture(document: PDDocument, byteBudget: Long = MAX_BYTES): List<ByteArray?> {
        require(byteBudget in 0L..MAX_BYTES)
        val count = document.numberOfPages
        require(count in 1..SecurityLimits.MAX_PAGE_COUNT)
        val hasher = GraphHasher(byteBudget)
        val result = ArrayList<ByteArray?>(count)
        for (page in document.pages) {
            check(result.size < count)
            val raw = page.resources?.cosObject
            result.add(hasher.sample(raw))
        }
        check(result.size == count)
        return result
    }

    private class GraphHasher(private var remaining: Long) {
        private val started = System.nanoTime()
        private var visits = 0
        private val memo = IdentityHashMap<COSBase, ByteArray>()
        private val visiting = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        private val buffer = ByteArray(8192)

        private fun checkTime() {
            if (System.nanoTime() - started > TIME_NS) throw Unmeasured()
        }

        private fun visit(depth: Int) {
            checkTime()
            if (++visits > MAX_VISITS || depth > MAX_DEPTH) throw Unmeasured()
        }

        private fun spend(bytes: Int) {
            if (bytes.toLong() > remaining) throw Unmeasured()
            remaining -= bytes.toLong()
        }

        private fun MessageDigest.int(value: Int) {
            for (shift in listOf(24, 16, 8, 0)) update((value ushr shift).toByte())
        }

        private fun MessageDigest.field(bytes: ByteArray) {
            spend(bytes.size)
            int(bytes.size)
            update(bytes)
        }

        private fun MessageDigest.string(value: String) = field(value.toByteArray(Charsets.UTF_8))

        private fun digest(raw: COSBase?, depth: Int): ByteArray {
            visit(depth)
            // Null at the page root means no effective resource dictionary.
            if (raw == null || raw is COSNull) {
                return MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0))
            }
            if (raw is COSObject) {
                if (!visiting.add(raw)) throw Unmeasured()
                try {
                    return digest(raw.`object` ?: throw Unmeasured(), depth + 1)
                } finally { visiting.remove(raw) }
            }
            memo[raw]?.let { return it }
            if (!visiting.add(raw)) throw Unmeasured()
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                when (raw) {
                    is COSStream -> {
                        hash.update(1.toByte())
                        dictionary(hash, raw, depth)
                        hash.update(2.toByte())
                        val streamHash = MessageDigest.getInstance("SHA-256")
                        raw.createRawInputStream().use { input ->
                            while (true) {
                                checkTime()
                                if (remaining <= 0L) throw Unmeasured()
                                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                if (n < 0) break
                                if (n == 0) throw Unmeasured()
                                spend(n)
                                streamHash.update(buffer, 0, n)
                            }
                        }
                        hash.update(streamHash.digest())
                    }
                    is COSDictionary -> {
                        hash.update(3.toByte())
                        dictionary(hash, raw, depth)
                    }
                    is COSArray -> {
                        hash.update(4.toByte())
                        if (raw.size() > MAX_VISITS - visits) throw Unmeasured()
                        hash.int(raw.size())
                        for (index in 0 until raw.size()) {
                            hash.update(digest(raw.get(index) ?: throw Unmeasured(), depth + 1))
                        }
                    }
                    is COSName -> { hash.update(5.toByte()); hash.string(raw.name) }
                    is COSString -> { hash.update(6.toByte()); hash.field(raw.bytes) }
                    is COSNumber -> { hash.update(7.toByte()); hash.string(raw.toString()) }
                    is COSBoolean -> { hash.update(8.toByte()); hash.update(if (raw.value) 1.toByte() else 0.toByte()) }
                    else -> throw Unmeasured()
                }
                val result = hash.digest()
                memo[raw] = result
                return result
            } finally { visiting.remove(raw) }
        }

        private fun dictionary(hash: MessageDigest, value: COSDictionary, depth: Int) {
            if (value.size() > MAX_VISITS - visits) throw Unmeasured()
            val keys = value.keySet().sortedBy { it.name }
            hash.int(keys.size)
            for (key in keys) {
                checkTime()
                hash.string(key.name)
                hash.update(digest(value.getItem(key) ?: throw Unmeasured(), depth + 1))
            }
        }

        fun sample(root: COSDictionary?): ByteArray? = try {
            digest(root, 0)
        } catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
    }
}
