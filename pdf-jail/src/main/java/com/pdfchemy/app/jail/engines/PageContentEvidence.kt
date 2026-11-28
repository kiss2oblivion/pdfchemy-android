package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.PageStreamEvidence
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.IOException
import java.security.MessageDigest
import java.util.IdentityHashMap
import org.json.JSONObject

/**
 * Compare ordered raw (encoded) /Contents streams of readable pages. Never
 * decompress streams, export hashes, or infer visual/content equivalence.
 * Shared stream identities are read once per document; both bytes and references
 * have operation-wide budgets. Exhausted or unreadable evidence is unknown.
 */
internal object PageContentEvidence {
    private const val MAX_RAW_BYTES = 32L * 1024 * 1024
    private const val MAX_REFERENCES = SecurityLimits.MAX_GRAPH_NODES

    fun capture(document: PDDocument, byteBudget: Long = MAX_RAW_BYTES): List<ByteArray?> {
        require(byteBudget in 0L..MAX_RAW_BYTES)
        require(document.numberOfPages in 1..SecurityLimits.MAX_PAGE_COUNT)
        var remaining = byteBudget
        var references = 0
        val memo = IdentityHashMap<COSStream, ByteArray?>()
        val buffer = ByteArray(8192)

        fun resolve(raw: COSBase?): COSBase? {
            var value = raw
            var depth = 0
            while (value is COSObject) {
                if (++depth > SecurityLimits.MAX_GRAPH_DEPTH) return null
                value = value.`object`
            }
            return value
        }

        fun streamDigest(stream: COSStream): ByteArray? {
            if (memo.containsKey(stream)) return memo[stream]
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = try {
                stream.createRawInputStream().use { input ->
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) return@use digest.digest()
                        if (count == 0) return@use null
                        remaining -= count.toLong()
                        digest.update(buffer, 0, count)
                    }
                    // Do not read even one additional byte beyond the budget.
                    null
                }
            } catch (_: IOException) { null }
            memo[stream] = hash
            return hash
        }

        fun pageDigest(raw: COSBase?): ByteArray? {
            val digest = MessageDigest.getInstance("SHA-256")
            if (raw == null || raw is COSNull) return digest.digest()
            val value = resolve(raw) ?: return null
            fun add(rawStream: COSBase?): Boolean {
                if (references >= MAX_REFERENCES) return false
                references++
                val stream = resolve(rawStream) as? COSStream ?: return false
                val hash = streamDigest(stream) ?: return false
                digest.update(hash)
                return true
            }
            when (value) {
                is COSStream -> if (!add(value)) return null
                is COSArray -> {
                    if (value.size() > MAX_REFERENCES - references) return null
                    for (index in 0 until value.size()) if (!add(value.get(index))) return null
                }
                else -> return null
            }
            return digest.digest()
        }

        val result = ArrayList<ByteArray?>(document.numberOfPages)
        for (page in document.pages) {
            check(result.size < SecurityLimits.MAX_PAGE_COUNT) { "Page evidence count exceeds quota" }
            result.add(pageDigest(page.cosObject.getItem(COSName.CONTENTS)))
        }
        check(result.size == document.numberOfPages) { "Page evidence count mismatch" }
        return result
    }

    fun compare(input: List<ByteArray?>, output: List<ByteArray?>): PageStreamEvidence {
        require(input.size == output.size && input.size in 1..SecurityLimits.MAX_PAGE_COUNT)
        var matched = 0
        var changed = 0
        var unknown = 0
        input.indices.forEach { index ->
            val a = input[index]
            val b = output[index]
            when {
                a == null || b == null -> unknown++
                a.contentEquals(b) -> matched++
                else -> changed++
            }
        }
        return PageStreamEvidence(matched, changed, unknown)
    }

    fun json(evidence: PageStreamEvidence): JSONObject = JSONObject()
        .put("matchingPages", evidence.matchingPages)
        .put("differentPages", evidence.differentPages)
        .put("unmeasuredPages", evidence.unmeasuredPages)
}
