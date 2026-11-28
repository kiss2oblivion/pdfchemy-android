package com.pdfchemy.app.jail.engines

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Collections
import java.util.IdentityHashMap
import java.util.ArrayDeque
import com.pdfchemy.app.security.SecurityLimits

internal class InspectionGraphLimitException : IllegalArgumentException("PDF inspection graph quota exceeded")

internal fun requireInspectionGraphLimit(allowed: Boolean) {
    if (!allowed) throw InspectionGraphLimitException()
}

/** Visits every reachable and pooled object, including outlines and associated-file dictionaries. */
object ActiveContentScrubber {
    data class Findings(var javascript: Int = 0, var launches: Int = 0, var actions: Int = 0, var untrustedUris: Int = 0, var attachments: Int = 0, var uris: Int = 0) {
        val total get() = javascript + launches + actions + untrustedUris + attachments + uris
        fun selected(js: Boolean, actions: Boolean, attachments: Boolean) = Findings(
            if (js) javascript else 0, if (actions) launches else 0, if (actions) this.actions else 0, if (actions) this.untrustedUris else 0,
            if (attachments) this.attachments else 0, if (actions) uris else 0)
    }
    private val activeActions = setOf("Launch", "GoToR", "GoToE", "SubmitForm", "ImportData", "Rendition", "Movie", "Sound", "RichMedia", "3D")
    private val activeSubtypes = setOf("RichMedia", "Movie", "Sound", "3D", "Screen")
    private val triggerBoundaries = setOf("Dest", "D", "Parent", "P", "Pages", "Root", "Prev")
    private val allowedUriSchemes = setOf("http", "https")
    private val attachmentCarrierKeys = listOf("EmbeddedFiles", "AF", "EF", "FS")
    private data class Pending(val base: COSBase, val depth: Int, val triggered: Boolean)

    private fun name(key: String) = COSName.getPDFName(key)

    /** Carriers reference attachments; the resolved FileSpec owns their logical identity. */
    private fun resolveAttachmentIdentity(dict: COSDictionary): COSDictionary? {
        if (dict.containsKey(name("EF"))) return dict
        if (dict.getNameAsString(COSName.SUBTYPE) != "FileAttachment") return null
        val fileSpec = dict.getDictionaryObject(name("FS")) as? COSDictionary ?: return null
        return fileSpec.takeIf { it.containsKey(name("EF")) }
    }

    fun inspect(document: PDDocument, scrub: Boolean = false, purgeJs: Boolean = true, purgeActions: Boolean = true, purgeAttachments: Boolean = true): Findings {
        val queue = ArrayDeque<Pending>()
        // Identity is recorded on enqueue, not dequeue. A reference shared by
        // thousands of pages is scheduled at most once per action context.
        // Keep normal and automatic-action contexts distinct: visiting a URI as
        // an ordinary link must never suppress a later triggered visit.
        val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val triggered = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val uriObjects = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val logicalAttachments = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun enqueue(base: COSBase, depth: Int, automatic: Boolean) {
            val scheduled = if (automatic) triggered else visited
            if (base in scheduled) return
            requireInspectionGraphLimit(visited.size + triggered.size < SecurityLimits.MAX_GRAPH_NODES &&
                depth <= SecurityLimits.MAX_GRAPH_DEPTH && queue.size < SecurityLimits.MAX_GRAPH_NODES)
            scheduled.add(base)
            queue.addLast(Pending(base, depth, automatic))
        }
        enqueue(document.documentCatalog.cosObject, 0, false)
        val pooled = document.document.objects
        requireInspectionGraphLimit(pooled.size <= SecurityLimits.MAX_GRAPH_NODES)
        pooled.forEach { enqueue(it, 0, false) }
        val findings = Findings()
        while (queue.isNotEmpty()) {
            val (base, depth, isTriggered) = queue.removeFirst()
            when (base) {
                is COSObject -> base.`object`?.let { enqueue(it, depth + 1, isTriggered) }
                is COSArray -> {
                    requireInspectionGraphLimit(base.size() <= SecurityLimits.MAX_GRAPH_NODES)
                    for (i in 0 until base.size()) enqueue(base.get(i), depth + 1, isTriggered)
                }
                is COSDictionary -> {
                    // Capture children before removing links, so orphaned carriers are scrubbed too.
                    requireInspectionGraphLimit(base.size() <= SecurityLimits.MAX_GRAPH_NODES)
                    // Read all edges before the dictionary can be mutated below.
                    // No key snapshot is needed here: this loop does not mutate base.
                    base.keySet().forEach { key -> base.getItem(key)?.let {
                        val child = if (it is COSObject) it.`object` else it
                        val automaticAction = key.name == "OpenAction" && child is COSDictionary && child.containsKey(COSName.S)
                        val inherited = isTriggered && key.name !in triggerBoundaries
                        enqueue(it, depth + 1, inherited || automaticAction || key.name == "AA")
                    } }
                    val action = base.getNameAsString(COSName.S)
                    val subtype = base.getNameAsString(COSName.SUBTYPE)
                    fun remove(key: String, enabled: Boolean) { if (scrub && enabled) base.removeItem(name(key)) }
                    if (action == "URI") {
                        if (uriObjects.add(base)) findings.uris++
                        val scheme = runCatching { java.net.URI(base.getString(name("URI"), "")).scheme?.lowercase() }.getOrNull()
                        if (isTriggered || scheme !in allowedUriSchemes) findings.untrustedUris++
                        listOf("S", "URI", "Next").forEach { remove(it, purgeActions) }
                    }
                    if (action == "JavaScript" || base.containsKey(name("JS")) || base.containsKey(name("JavaScript"))) {
                        findings.javascript++
                        remove("JS", purgeJs); remove("JavaScript", purgeJs)
                        if (action == "JavaScript") { remove("S", purgeJs); remove("Next", purgeJs) }
                    }
                    if (action in activeActions || subtype in activeSubtypes || base.containsKey(name("XFA"))) {
                        if (action == "Launch") findings.launches++ else findings.actions++
                        if (action in activeActions) {
                            listOf("S", "URI", "F", "D", "Next", "Win", "R", "AN", "OP").forEach { remove(it, purgeActions) }
                        }
                        if (subtype in activeSubtypes && scrub && purgeActions) base.setName(COSName.SUBTYPE, "Text")
                        listOf("XFA", "RichMediaContent", "RichMediaSettings", "3DD", "3DA", "Movie", "Sound").forEach { remove(it, purgeActions) }
                    }
                    resolveAttachmentIdentity(base)?.let { identity ->
                        if (logicalAttachments.add(identity)) findings.attachments++
                    }
                    // Removal remains broader than counting: sever every carrier, including orphans.
                    if (subtype == "FileAttachment" || attachmentCarrierKeys.any { base.containsKey(name(it)) }) {
                        attachmentCarrierKeys.forEach { remove(it, purgeAttachments) }
                        if (subtype == "FileAttachment" && scrub && purgeAttachments) base.setName(COSName.SUBTYPE, "Text")
                    }
                }
            }
        }
        return findings
    }
}
