package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class HistoryRepositoryTest {

    private lateinit var context: Context
    private lateinit var historyRepository: HistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        historyRepository = HistoryRepository(context)
        historyRepository.clearHistory() // start fresh
    }

    @After
    fun tearDown() {
        historyRepository.clearHistory()
    }

    @Test
    fun addAndGetHistory_worksCorrectly() {
        val uri = Uri.parse("content://dummy/file.pdf")
        historyRepository.addHistoryItem(uri, "MyDoc.pdf", "Compressed")

        val history = historyRepository.getHistory()
        assertEquals(1, history.size)
        assertEquals("MyDoc.pdf", history[0].name)
        assertEquals("Compressed", history[0].action)
        assertEquals("content://dummy/file.pdf", history[0].uriString)
    }

    @Test
    fun addHistory_avoidsDuplicatesByUri() {
        val uri = Uri.parse("content://dummy/duplicate.pdf")
        historyRepository.addHistoryItem(uri, "Doc1.pdf", "Compressed")
        // Adding the same URI should replace the old entry
        historyRepository.addHistoryItem(uri, "Doc1_Updated.pdf", "Merged")

        val history = historyRepository.getHistory()
        assertEquals(1, history.size)
        assertEquals("Doc1_Updated.pdf", history[0].name)
        assertEquals("Merged", history[0].action)
    }

    @Test
    fun history_keepsOnly20Items() {
        for (i in 1..25) {
            val uri = Uri.parse("content://dummy/file_$i.pdf")
            historyRepository.addHistoryItem(uri, "File $i", "Action")
        }

        val history = historyRepository.getHistory()
        assertEquals(20, history.size)
        
        // Since list is sorted descending by timestamp, the 25th item should be first
        assertEquals("File 25", history[0].name)
        // And the 6th item should be the last in the history list (items 1-5 dropped)
        assertEquals("File 6", history[19].name)
    }

    @Test
    fun clearHistory_removesAllItems() {
        val uri = Uri.parse("content://dummy/file.pdf")
        historyRepository.addHistoryItem(uri, "To Delete", "Action")
        
        assertTrue(historyRepository.getHistory().isNotEmpty())
        
        historyRepository.clearHistory()
        
        assertTrue(historyRepository.getHistory().isEmpty())
    }
}
