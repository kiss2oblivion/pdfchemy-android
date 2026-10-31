# Privacy Policy — PDFchemy Android

Updated: October 31, 2026

Developer: Andrei Ioan Cucoș. Privacy contact: cucosandreiioan@gmail.com. This policy covers the Android app com.pdfchemy.app.

## Document processing and access

PDFchemy processes documents on your device and does not operate a document-upload or cloud-conversion backend. The app does not send document contents, filenames, passwords or document metadata as advertising or app-analytics parameters. Document access normally uses Android's Storage Access Framework: you choose the documents and output folder. Other apps can also send documents to PDFchemy through Android sharing or opening actions.

Processing normally publishes a separate output copy. Failed or cancelled work must not replace an existing source or destination. A document provider controls its own storage and network behavior; selecting a cloud-backed provider can cause that provider to transfer files under its own policy. Review the chosen operation and saved output before relying on it. Redaction and sanitization modes differ; this policy does not certify every document or promise removal of information outside the selected operation.

## Temporary files and history

The app creates private staging files, rendered previews and processing outputs to complete requested operations. It cleans temporary work when it is no longer owned by an active operation and during relevant startup cleanup. Interrupted work or provider deletion errors can leave private temporary files until cleanup can complete. Document history, reader positions, preferences and cached Premium status are stored locally. Clearing or disabling recent history removes history records, not source documents, saved outputs or retained recipient copies.

## Shared copies and deletion

Sharing may create a private retained copy exposed through a narrowly granted Android content URI. Retained sharing has a 64-copy / 250 MiB budget; a new share is refused when the budget cannot accommodate it. Completed shared copies do not automatically expire and are not silently evicted to make room.

Settings → Manage shared copies shows storage use and offers explicit cleanup. Currently active transfers and tracked open readers are protected. Some older copies whose ownership cannot be proven also remain protected. Deleting an eligible inactive copy prevents future opening of that shared URI; a recipient may still hold or have saved its own copy. The app does not revoke recipient permissions as an automatic cleanup mechanism. Deletion failures are reported.

You control source documents and exported copies through their document provider. Removing PDFchemy's app data or uninstalling removes its private settings, history and copies; it does not delete exported files or copies independently retained by recipients. Contact the relevant provider or recipient about their retention.

## Advertising and privacy choices

The free edition uses Google AdMob and the User Messaging Platform (UMP). App-owned document analytics and document-content logging are not enabled. This is separate from third-party SDK activity: Google advertising services can process IP addresses (including approximate location derived from IP), app/ad interactions, diagnostic information and device, advertising, app-set and, where applicable, account-related identifiers for advertising, analytics/measurement and fraud prevention. The exact data depends on SDK behavior, device settings and privacy choices. These SDKs use network access and their own data-handling policies.

Advertising requests follow eligibility reported by Google's consent platform. Where required, reopen Privacy choices in Settings → Privacy Policy. Viewing this policy or closing it does not grant advertising consent. Android also provides controls to reset or delete the advertising ID. Premium entitlement disables advertising in PDFchemy. Full-screen interstitial ads are disabled in Reader and Reflow to prevent reading interruptions.

Google privacy policy: https://policies.google.com/privacy
Google advertising information: https://policies.google.com/technologies/ads

## Google Play purchases

Google Play handles Premium payment and purchase records. PDFchemy receives the product, purchase state and purchase token needed to process the purchase and acknowledgement. The app keeps a local entitlement cache for offline use and rechecks Google Play when a connection is available. PDFchemy does not collect payment-card details or operate a separate purchase-account server. Google Play controls its purchase-data retention and refund processes.

## Audio, OCR and scanning

Audio export passes selected document text to a separate installed Android text-to-speech engine. PDFchemy selects a voice that the engine reports does not require a network connection. That declaration is provided by the engine and is not a guarantee about that vendor's network behavior. Temporary PCM/WAV files remain private until the selected output is published and are then cleaned; abandoned audio stages are cleaned after restart.

Document processing and OCR run on-device. The ML Kit document scanner uses Google Play services' camera access, not an app-owned camera permission. Google Play services can download scanner models, logic and interface components, so initial setup may need a network connection. Your installed text-to-speech engine, document provider and Google services have their own privacy policies.

## Security and retention limits

The app uses Android private storage, isolated processing workers and scoped document/URI access to limit access to document data. Credentials used by a requested operation are not intentionally sent to an app processing server. Third-party SDK network traffic is subject to those SDKs' transport and security practices, including Google's documented TLS handling.

The app does not provide an app-account registration service. For local data deletion, use the relevant history/shared-copy controls or Android app-data controls. Google, providers and recipients may retain their own data under their policies. For questions about PDFchemy's handling of your data or this policy, email cucosandreiioan@gmail.com.

Public Android source and issue tracker: https://github.com/kiss2oblivion/pdfchemy-android
