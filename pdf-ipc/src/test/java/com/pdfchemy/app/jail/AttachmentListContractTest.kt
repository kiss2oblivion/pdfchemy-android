package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.AttachmentListContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AttachmentListContractTest {
    private fun item(id: String) = JSONObject().put("attachmentId", id).put("name", "same.txt")
        .put("sizeBytes", 12).put("mimeType", "text/plain")
    private fun validate(array: JSONArray) = WorkerResponseValidator.validate("ATTACHMENT_LIST", array.toString()) as AttachmentListContract
    @Test fun distinctIdentifiersSurviveIdenticalDisplayNamesAndSizes() {
        val contract = validate(JSONArray().put(item("key-1")).put(item("key-2")))
        assertEquals(listOf("key-1", "key-2"), contract.attachments.map { it.attachmentId })
    }
    @Test fun missingIdentifierIsRejected() {
        val entry = item("key"); entry.remove("attachmentId")
        assertNotNull(runCatching { validate(JSONArray().put(entry)) }.exceptionOrNull())
    }
    @Test fun duplicateIdentifiersAreRejected() {
        assertNotNull(runCatching { validate(JSONArray().put(item("key")).put(item("key"))) }.exceptionOrNull())
    }
    @Test fun invalidAndOversizedIdentifiersAreRejected() {
        for (id in listOf<Any>(42, "x".repeat(1025))) {
            assertNotNull(runCatching { validate(JSONArray().put(item("key").put("attachmentId", id))) }.exceptionOrNull())
        }
    }
}
