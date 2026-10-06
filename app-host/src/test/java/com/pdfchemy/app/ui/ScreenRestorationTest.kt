package com.pdfchemy.app.ui

import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import com.pdfchemy.app.Screen
import com.pdfchemy.app.ScreenSaver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenRestorationTest {
    @Test fun everyToolDestinationRoundTripsThroughSavedNavigation() {
        val scope = SaverScope { true }
        for (type in Screen::class.sealedSubclasses) {
            val screen = type.objectInstance ?: type.constructors.single().callBy(emptyMap())
            val saved = with(ScreenSaver) { scope.save(screen) }
            assertNotNull("Missing serialization for ${type.simpleName}", saved)
            assertEquals("Wrong restored route for ${type.simpleName}", screen, ScreenSaver.restore(saved!!))
        }
    }

    @Test fun readerEditorAndUnlockKeepTheDocumentUriAcrossRestoration() {
        val uri = Uri.parse("content://provider/document/example.pdf")
        val scope = SaverScope { true }
        for (screen in listOf(Screen.PdfReader(uri), Screen.PdfEditor(uri), Screen.ReflowReader(uri), Screen.UnlockPdf(uri))) {
            val saved = with(ScreenSaver) { scope.save(screen) }!!
            assertEquals(screen, ScreenSaver.restore(saved))
        }
    }
}
