package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.gson.GsonBuilder
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FindReplaceColorWireTest {
    @After fun resetMocks() = unmockkAll()

    @Test fun `replacement colors keep their wire names when reflected fields are renamed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Uri.parse("file:///source.pdf")
        val destination = Uri.parse("file:///replacement.pdf")
        val payload = slot<Any>()
        mockkObject(JailEngineBridge)
        coEvery { JailEngineBridge.call(context, "REPLACE_ALL", source, destination, capture(payload), null) } returns ReplaceAllContract(1)
        assertEquals(1, PdfFindAndReplaceEngine.replaceAll(context, source, destination, "ACME", "NEW", true,
            Triple(.2f, .3f, .4f), Triple(.7f, .8f, .9f)).getOrThrow())
        // Simulate a shrinker renaming reflected fields. Literal JSON object keys must survive.
        val json = GsonBuilder().setFieldNamingStrategy { "renamed_" + it.name }.create().toJson(payload.captured)
        val root = JSONObject(json)
        for ((key, values) in listOf("maskColorRgb" to listOf(.2, .3, .4), "textColorRgb" to listOf(.7, .8, .9))) {
            val channels = root.getJSONObject(key)
            assertEquals(setOf("first", "second", "third"), channels.keys().asSequence().toSet())
            for ((index, name) in listOf("first", "second", "third").withIndex())
                assertEquals(values[index], channels.getDouble(name), .00001)
        }
        assertEquals("ACME", root.getString("findText")); assertEquals("NEW", root.getString("replaceText"))
        assertTrue(root.getBoolean("matchCase"))
    }
}
