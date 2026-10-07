# Play foreground-service declaration draft

Submission and demonstration video are not completed. Use these descriptions for the actual behavior; this document is not evidence of Play approval.

Exact implementation: `app-host/src/main/AndroidManifest.xml` declares the private `AudioExportService` with `mediaProcessing|dataSync`, and FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PROCESSING / FOREGROUND_SERVICE_DATA_SYNC permissions. `logic/audio/AudioForegroundPolicy.kt` selects mediaProcessing on API 35+, dataSync for local file processing on API 29–34, and the older foreground-service overload on API 24–28. Only one type is selected at runtime.

| Form field | Proposed factual answer |
|---|---|
| Feature | User initiated PDF text-to-WAV audio export from Reader/Reflow |
| mediaProcessing use | Convert locally extracted text into PCM through Android TTS, assemble a validated WAV and publish it to the user's chosen document destination |
| dataSync compatibility use | Local file processing for the same export on older Android versions; not an unrelated background transfer |
| User initiation | The foreground user chooses Audio Export and a SAF destination; an opaque staged job ID starts the service |
| Why foreground service | Long speech synthesis/file assembly must complete when the user switches apps; a persistent notification exposes cancellation |
| User impact if interrupted | The requested WAV is unavailable; partial staging/destination is cleaned up best effort and failure/cancellation is reported |
| Completion / cancellation | Stop TTS, reject stale callbacks, drain/close the writer, clean staging, remove foreground notification and stop service |
| Timeout | API 35+ media-processing timeout is terminal failure, not indefinite suspension; stopForeground/stopSelf occur promptly and cleanup follows |
| Network / privacy | PDFchemy stages text and PCM locally. Selectable voices are reported by Android as not requiring a network connection. This is not a guarantee about third-party TTS engines. A user-selected cloud SAF provider may upload the published WAV |

Record a short physical-device video on API 35+ showing Reader text → export → SAF filename → export progress → Home/background → ongoing notification → completion and playable WAV. Record a second cancellation segment showing no successful partial output. Show the older API 34 compatibility path if the Console requests dataSync evidence. Use neutral test text and avoid personal documents.

Declare the types requested by the uploaded manifest in Play Console App content. Check the final uploaded bundle's manifest rather than an earlier APK. [Play's FGS requirements](https://support.google.com/googleplay/android-developer/answer/13392821) require feature descriptions, user impact, and a demonstration; [Android's service types](https://developer.android.com/develop/background-work/services/fgs/service-types) describe media processing and local-file dataSync use cases.
