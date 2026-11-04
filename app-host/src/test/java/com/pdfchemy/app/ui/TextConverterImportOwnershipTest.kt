package com.pdfchemy.app.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.pdfchemy.app.logic.TextFormatConverter
import com.pdfchemy.app.ui.textconverter.TextConverterViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextConverterImportOwnershipTest {
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private lateinit var vm: TextConverterViewModel
    private val releases = mutableListOf<CountDownLatch>()
    private class HeldInput(text: String, val fail: Boolean = false) : ByteArrayInputStream(text.toByteArray()) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var closed = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            if (fail) throw IOException("controlled obsolete provider failure")
            return super.read(buffer, offset, length)
        }
        override fun close() { closed = true; super.close() }
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.contentResolver } returns resolver
        vm = TextConverterViewModel()
    }
    @After fun cleanup() {
        releases.forEach { it.countDown() }; unmockkAll(); Dispatchers.resetMain()
    }
    private fun held(uri: Uri, text: String, failure: Boolean = false): HeldInput = HeldInput(text, failure).also {
        releases.add(it.release); every { resolver.openInputStream(uri) } returns it
    }
    private suspend fun value(text: String) = withTimeout(5_000) { vm.inputText.first { it == text } }
    private fun jobs() = vm.viewModelScope.coroutineContext[Job]!!.children.toList()

    @Test fun requestsABCCompletingCABPublishOnlyCAndItsFormat() = runBlocking<Unit> {
        val a = Uri.parse("content://fixture/a.json"); val b = Uri.parse("content://fixture/b.csv"); val c = Uri.parse("content://fixture/c.xml")
        val first = held(a, "A"); val second = held(b, "B")
        every { resolver.openInputStream(c) } returns "C".byteInputStream()
        vm.loadFile(context, a); check(first.entered.await(5, TimeUnit.SECONDS))
        val firstJob = jobs().single()
        vm.loadFile(context, b); check(second.entered.await(5, TimeUnit.SECONDS))
        vm.loadFile(context, c); value("C")
        val pending = jobs(); first.release.countDown(); firstJob.join()
        assertEquals("C", vm.inputText.value)
        assertFalse("B remains held until after A finishes", second.closed)
        second.release.countDown(); pending.joinAll()
        assertEquals("C", vm.inputText.value); assertEquals(TextFormatConverter.Format.XML, vm.inputFormat.value)
        assertEquals(TextConverterViewModel.UiState.Idle, vm.uiState.value)
        assertTrue(first.closed); assertTrue(second.closed)
    }
    @Test fun obsoleteProviderFailureCannotReplaceLatestSuccessState() = runBlocking<Unit> {
        val a = Uri.parse("content://fixture/a.txt"); val b = Uri.parse("content://fixture/b.txt")
        val first = held(a, "old", failure = true)
        every { resolver.openInputStream(b) } returns "latest".byteInputStream()
        vm.loadFile(context, a); check(first.entered.await(5, TimeUnit.SECONDS))
        vm.loadFile(context, b); value("latest")
        val pending = jobs(); first.release.countDown(); pending.joinAll()
        assertEquals("latest", vm.inputText.value); assertEquals(TextConverterViewModel.UiState.Idle, vm.uiState.value)
        assertTrue(first.closed)
    }
    @Test fun repeatedSameUriStillHasDistinctOwnership() = runBlocking<Unit> {
        val uri = Uri.parse("content://fixture/same.txt"); val first = HeldInput("old")
        releases.add(first.release)
        every { resolver.openInputStream(uri) } returnsMany listOf(first, "new".byteInputStream())
        vm.loadFile(context, uri); check(first.entered.await(5, TimeUnit.SECONDS))
        vm.loadFile(context, uri); value("new")
        val pending = jobs(); first.release.countDown(); pending.joinAll()
        assertEquals("new", vm.inputText.value); assertTrue(first.closed)
    }
    @Test fun clearingViewModelRejectsLateInputAndClosesStream() = runBlocking<Unit> {
        val uri = Uri.parse("content://fixture/slow.txt"); val first = held(uri, "obsolete")
        vm.setInputText("retained"); val store = ViewModelStore().also { it.put("converter", vm) }
        vm.loadFile(context, uri); check(first.entered.await(5, TimeUnit.SECONDS))
        val pending = jobs(); store.clear(); first.release.countDown(); pending.joinAll()
        assertEquals("retained", vm.inputText.value); assertTrue(first.closed)
        assertFalse(vm.uiState.value is TextConverterViewModel.UiState.Success)
        assertFalse(vm.uiState.value is TextConverterViewModel.UiState.Error)
    }
    @Test fun latestProviderFailureIsVisibleAndKeepsExistingText() = runBlocking<Unit> {
        every { resolver.openInputStream(any()) } throws IOException("current failure")
        vm.setInputText("retained"); vm.loadFile(context, Uri.parse("content://fixture/current.txt"))
        jobs().joinAll()
        assertEquals("retained", vm.inputText.value)
        assertTrue(vm.uiState.value is TextConverterViewModel.UiState.Error)
    }
}
