package com.pdfchemy.app.security;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Separate installed test engine. Records only synthetic test text, never ships in the app. */
public class SpeechFixtureService extends TextToSpeechService {
    private static final Object LOCK = new Object();
    private static boolean networkOnly = true;
    private static boolean offlineInstalled = true;
    private static Locale locale = Locale.US;
    private static int queries;
    private static final ArrayList<String> texts = new ArrayList<>();
    private static final ArrayList<String> requestedVoices = new ArrayList<>();

    @Override public List<Voice> onGetVoices() {
        synchronized (LOCK) {
            queries++;
            Voice network = new Voice("network", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, true, Collections.emptySet());
            Voice offline = new Voice("offline", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false,
                offlineInstalled ? Collections.emptySet() : Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
            return networkOnly ? Collections.singletonList(network) : Arrays.asList(network, offline);
        }
    }
    @Override public String onGetDefaultVoiceNameFor(String language, String country, String variant) { return "network"; }
    @Override public int onIsValidVoiceName(String name) {
        synchronized (LOCK) { return "network".equals(name) || (!networkOnly && "offline".equals(name)) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR; }
    }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected String[] onGetLanguage() {
        synchronized (LOCK) { return new String[] {locale.getISO3Language(), locale.getISO3Country(), ""}; }
    }
    @Override protected int onIsLanguageAvailable(String language, String country, String variant) { return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE; }
    @Override protected int onLoadLanguage(String language, String country, String variant) { return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE; }
    @Override protected void onStop() { }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        synchronized (LOCK) {
            texts.add(request.getCharSequenceText().toString());
            requestedVoices.add(request.getVoiceName());
        }
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        byte[] audio = new byte[3200];
        callback.audioAvailable(audio, 0, audio.length);
        callback.done();
    }

    public static class Control extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            synchronized (LOCK) {
                if ("configure".equals(method)) {
                    networkOnly = extras.getBoolean("networkOnly");
                    offlineInstalled = extras.getBoolean("offlineInstalled", true);
                    locale = Locale.forLanguageTag(extras.getString("locale", "en-US"));
                    texts.clear(); requestedVoices.clear(); queries = 0;
                }
                Bundle result = new Bundle();
                result.putInt("queries", queries);
                result.putStringArrayList("texts", new ArrayList<>(texts));
                result.putStringArrayList("voices", new ArrayList<>(requestedVoices));
                return result;
            }
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
