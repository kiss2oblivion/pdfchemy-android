package com.pdfchemy.app.jail.engines

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Collections
import java.util.IdentityHashMap
import java.util.ArrayDeque
import com.pdfchemy.app.security.SecurityLimits

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
    private fun name(key: String) = COSName.getPDFName(key)

    fun inspect(document: PDDocument, scrub: Boolean = false, purgeJs: Boolean = true, purgeActions: Boolean = true, purgeAttachments: Boolean = true): Findings {
        val queue = ArrayDeque<Triple<COSBase, Int, Boolean>>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val triggered = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val uriObjects = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        queue.add(Triple(document.documentCatalog.cosObject, 0, false))
        require(document.document.objects.size <= SecurityLimits.MAX_GRAPH_NODES) { "PDF object quota exceeded" }
        document.document.objects.forEach { queue.add(Triple(it, 0, false)) }
        val findings = Findings()
        while (queue.isNotEmpty()) {
            val (base, depth, isTriggered) = queue.removeFirst()
            if (!(if (isTriggered) triggered else visited).add(base)) continue
            require(visited.size + triggered.size <= SecurityLimits.MAX_GRAPH_NODES && depth <= SecurityLimits.MAX_GRAPH_DEPTH && queue.size <= SecurityLimits.MAX_GRAPH_NODES) { "PDF traversal quota exceeded" }
            when (base) {
                is COSObject -> base.`object`?.let { queue.add(Triple(it, depth + 1, isTriggered)) }
                is COSArray -> {
                    require(base.size() <= SecurityLimits.MAX_GRAPH_NODES)
                    for (i in 0 until base.size()) queue.add(Triple(base.get(i), depth + 1, isTriggered))
                }
                is COSDictionary -> {
                    // Capture children before removing links, so orphaned carriers are scrubbed too.
                    require(base.size() <= SecurityLimits.MAX_GRAPH_NODES)
                    base.keySet().toList().forEach { key -> base.getItem(key)?.let {
                        val child = if (it is COSObject) it.`object` else it
                        val automaticAction = key.name == "OpenAction" && child is COSDictionary && child.containsKey(COSName.S)
                        val inherited = isTriggered && key.name !in setOf("Dest", "D", "Parent", "P", "Pages", "Root", "Prev")
                        queue.add(Triple(it, depth + 1, inherited || automaticAction || key.name == "AA"))
                    } }
                    val action = base.getNameAsString(COSName.S)
                    val subtype = base.getNameAsString(COSName.SUBTYPE)
                    fun remove(key: String, enabled: Boolean) { if (scrub && enabled) base.removeItem(name(key)) }
                    if (action == "URI") {
                        if (uriObjects.add(base)) findings.uris++
                        val scheme = runCatching { java.net.URI(base.getString(name("URI"), "")).scheme?.lowercase() }.getOrNull()
                        if (isTriggered || scheme !in setOf("http", "https")) findings.untrustedUris++
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
                    
                    if (subtype == "FileAttachment" || listOf("EmbeddedFiles", "AF", "EF", "FS").any { base.containsKey(name(it)) }) {
                        findings.attachments++
                        listOf("EmbeddedFiles", "AF", "EF", "FS").forEach { remove(it, purgeAttachments) }
                        if (subtype == "FileAttachment" && scrub && purgeAttachments) base.setName(COSName.SUBTYPE, "Text")
                    }
                }
            }
        }
        return findings
    }
}
