package com.pdfchemy.app.logic

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineSpeechTest {
    private fun voice(name: String, locale: Locale = Locale.US, network: Boolean = false, installed: Boolean = true) =
        Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network,
            if (installed) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
    private val offline = voice("offline")
    private val network = voice("network", network = true)
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private var selected: Voice? = null
    private val requests = mutableListOf<Pair<String, String>>()
    private lateinit var session: OfflineReadAloudSession

    @Before fun setup() {
        every { engine.voices } returns setOf(network, offline)
        every { engine.voice } answers { selected }
        every { engine.setVoice(any()) } answers { selected = firstArg(); TextToSpeech.SUCCESS }
        every { engine.setSpeechRate(any()) } returns TextToSpeech.SUCCESS
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers {
            requests.add(firstArg<CharSequence>().toString() to arg<String>(3))
            TextToSpeech.SUCCESS
        }
        session = OfflineReadAloudSession(engine)
        session.replaceParagraphs(listOf("First synthetic paragraph", "Second synthetic paragraph"))
    }
    @After fun cleanup() { session.close(); unmockkAll() }

    @Test fun networkOnlyOrDownloadableVoicesAreNotSelected() {
        assertNull(OfflineSpeechPolicy.selectVoice(listOf(network, voice("download", installed = false)), Locale.US))
        every { engine.voices } returns setOf(voice("download", installed = false))
        assertNull(OfflineSpeechPolicy.configure(engine, Locale.US))
        verify(exactly = 0) { engine.setVoice(any()) }
    }

    @Test fun exactLocalePrecedesStableSameLanguageFallbackAndOtherLanguagesAreRejected() {
        val british = voice("a-british", Locale.UK)
        val american = voice("z-american", Locale.US)
        val voices = listOf(network, british, american)
        assertSame(american, OfflineSpeechPolicy.selectVoice(voices, Locale.US))
        assertSame(british, OfflineSpeechPolicy.selectVoice(voices, Locale.CANADA))
        assertNull(OfflineSpeechPolicy.selectVoice(voices, Locale.FRANCE))
    }

    @Test fun speechRequestedDuringInitializationWaitsForOfflineSelection() {
        assertFalse(session.play(0))
        assertTrue(requests.isEmpty())
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertEquals(listOf("First synthetic paragraph"), requests.map { it.first })
        assertSame(offline, selected)
        assertTrue(session.state.value.playing)
    }

    @Test fun initializationFailureDoesNotSubmitPendingText() {
        session.play(0)
        session.initialize(TextToSpeech.ERROR, Locale.US)
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
        assertFalse(session.play(0))
        assertTrue(requests.isEmpty())
        verify(exactly = 0) { engine.setVoice(any()) }
    }

    @Test fun missingOfflineVoiceDoesNotSubmitText() {
        every { engine.voices } returns setOf(network)
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertFalse(session.play(0))
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
        assertTrue(requests.isEmpty())
    }

    @Test fun rejectedVoiceDoesNotSubmitText() {
        every { engine.setVoice(any()) } returns TextToSpeech.ERROR
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertFalse(session.play(0))
        assertTrue(requests.isEmpty())
    }

    @Test fun missingReportedVoiceDoesNotSubmitTextDespiteSuccessfulSetVoice() {
        every { engine.voice } returns null
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertFalse(session.play(0))
        assertTrue(requests.isEmpty())
    }

    @Test fun continuationRechecksVoiceAndRefusesNetworkFallback() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        session.play(0)
        selected = network
        session.onDone(requests.single().second)
        assertEquals(1, requests.size)
        assertFalse(session.state.value.playing)
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
    }

    @Test fun validContinuationUsesFreshIdsAndCompletesAtTheLastParagraph() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        session.play(0)
        val first = requests.single().second
        session.onDone(first)
        assertEquals(2, requests.size)
        val second = requests.last().second
        assertNotEquals(first, second)
        assertEquals(1, session.state.value.paragraphIndex)
        session.onDone(first)
        assertEquals(2, requests.size)
        session.onDone(second)
        assertFalse(session.state.value.playing)
    }

    @Test fun skipAndPauseRetireEarlierCallbacks() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        session.play(0)
        val first = requests.single().second
        session.play(1)
        val second = requests.last().second
        session.onDone(first)
        assertEquals(2, requests.size)
        session.stop()
        session.onDone(second)
        session.onError(second)
        assertEquals(2, requests.size)
        assertEquals(OfflineReadAloudSession.Availability.READY, session.state.value.availability)
        assertFalse(session.state.value.playing)
    }

    @Test fun documentReplacementRetiresCallbacksAndUsesOnlyNewParagraphs() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        session.play(0)
        val old = requests.single().second
        session.replaceParagraphs(listOf("Replacement synthetic paragraph"))
        session.onDone(old)
        assertEquals(1, requests.size)
        session.play(0)
        assertEquals("Replacement synthetic paragraph", requests.last().first)
    }

    @Test fun disposalBeforeInitializationCancelsQueuedSpeechAndLateCallbacks() {
        session.play(0)
        session.close()
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        session.onDone("late")
        session.onError("late")
        session.close()
        assertTrue(requests.isEmpty())
        assertEquals(OfflineReadAloudSession.Availability.CLOSED, session.state.value.availability)
        verify(exactly = 1) { engine.shutdown() }
    }

    @Test fun engineErrorStopsPlaybackAndPreventsAutomaticContinuation() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        session.play(0)
        val id = requests.single().second
        session.onError(id)
        session.onDone(id)
        assertEquals(1, requests.size)
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
    }

    @Test fun initializationDeadlineRejectsPendingPlayAndLateSuccess() {
        session.play(0)
        session.initializationTimedOut()
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertTrue(requests.isEmpty())
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
    }

    @Test fun pendingPlayDoesNotTransferToAReplacementDocumentWithIdenticalText() {
        val paragraphs = listOf("Same synthetic paragraph")
        session.replaceParagraphs(paragraphs, "first document")
        session.play(0)
        session.replaceParagraphs(paragraphs, "second document")
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertTrue(requests.isEmpty())
        assertFalse(session.state.value.playing)
    }

    @Test fun pauseWhileInitializingRetiresPendingPlay() {
        session.play(0)
        session.stop()
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        assertTrue(requests.isEmpty())
        assertEquals(OfflineReadAloudSession.Availability.READY, session.state.value.availability)
    }

    @Test fun engineRejectionDoesNotContinuePlayback() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } returns TextToSpeech.ERROR
        assertFalse(session.play(0))
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
        assertTrue(requests.isEmpty())
    }

    @Test fun speechRateFailurePreventsDocumentSubmission() {
        session.initialize(TextToSpeech.SUCCESS, Locale.US)
        every { engine.setSpeechRate(any()) } returns TextToSpeech.ERROR
        assertFalse(session.play(0))
        assertEquals(OfflineReadAloudSession.Availability.UNAVAILABLE, session.state.value.availability)
        assertTrue(requests.isEmpty())
    }

    @Test fun changedReportedLocaleOrInstallationStatusCannotBeAccepted() {
        assertFalse(OfflineSpeechPolicy.matches(voice("offline", Locale.UK), offline))
        assertFalse(OfflineSpeechPolicy.matches(voice("offline", installed = false), offline))
        assertFalse(OfflineSpeechPolicy.matches(network, offline))
    }
}
