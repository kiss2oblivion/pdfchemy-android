package com.pdfchemy.app.logic

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TurkishStringFormattingTest {

    private fun turkishContext(): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag("tr")))
        return app.createConfigurationContext(config)
    }

    @Test
    fun testCompressReducedFormatsCorrectlyUnderTurkishLocale() {
        val context = turkishContext()
        val formatted = context.getString(R.string.msg_compress_reduced, 25, "1.2 MB")

        assertTrue("Expected %25 in '$formatted'", formatted.contains("%25"))
        assertTrue("Expected 1.2 MB in '$formatted'", formatted.contains("1.2 MB"))
        assertFalse("Unformatted token %1\$d found in '$formatted'", formatted.contains("%1\$d"))
        assertFalse("Unformatted token %2\$s found in '$formatted'", formatted.contains("%2\$s"))
    }

    @Test
    fun testImageBatchCompletedSummaryFormatsCorrectlyUnderTurkishLocale() {
        val context = turkishContext()
        val formatted = context.getString(R.string.image_batch_completed_summary, 1, 2, "10 MB", "5 MB", 50)

        assertTrue("Expected 1 / 2 in '$formatted'", formatted.contains("1 / 2"))
        assertTrue("Expected 10 MB in '$formatted'", formatted.contains("10 MB"))
        assertTrue("Expected 5 MB in '$formatted'", formatted.contains("5 MB"))
        assertTrue("Expected %50 in '$formatted'", formatted.contains("%50"))
        assertFalse("Unformatted token %1\$d found in '$formatted'", formatted.contains("%1\$d"))
        assertFalse("Unformatted token %2\$d found in '$formatted'", formatted.contains("%2\$d"))
        assertFalse("Unformatted token %3\$s found in '$formatted'", formatted.contains("%3\$s"))
        assertFalse("Unformatted token %4\$s found in '$formatted'", formatted.contains("%4\$s"))
        assertFalse("Unformatted token %5\$d found in '$formatted'", formatted.contains("%5\$d"))
    }

    @Test
    fun testLabelOpacityFormatsCorrectlyUnderTurkishLocale() {
        val context = turkishContext()
        val formatted = context.getString(R.string.label_opacity, 50)

        assertTrue("Expected %50 in '$formatted'", formatted.contains("%50"))
        assertFalse("Unformatted token %1\$d found in '$formatted'", formatted.contains("%1\$d"))
    }
}
