# Add project specific ProGuard rules here.

# PdfBox-Android optional dependencies
-dontwarn com.gemalto.jp2.**
-dontwarn javax.xml.stream.**
-dontwarn aQute.bnd.annotation.spi.**
-dontwarn org.codehaus.stax2.**

# Jackson Dataformat & Kotlin module reflection
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn com.fasterxml.jackson.dataformat.**
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses

# Gson 2.10.1 does not supply the full-mode TypeToken rules. Both editor
# entrypoints recover Map<Integer, PageModification> from anonymous subclasses.
# https://google.github.io/gson/Troubleshooting.html#illegalstateexception-typetoken-must-be-created-with-a-type-argument
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# These shared DTOs are reflected by Gson across the host/worker wire. Preserve
# their field names, constructors and nested collection signatures. Worker JSON
# validation also reads the result keys literally; broad application keeps are
# unnecessary. scripts/check_release_gson_contract.py checks the actual DEX.
-keep class com.pdfchemy.app.logic.PageModification { *; }
-keep class com.pdfchemy.app.logic.DrawingPoint { *; }
-keep class com.pdfchemy.app.logic.DrawingPath { *; }
-keep class com.pdfchemy.app.logic.TextAnnotation { *; }
-keep class com.pdfchemy.app.logic.StampAnnotation { *; }
-keep class com.pdfchemy.app.logic.StampType { *; }
-keep class com.pdfchemy.app.logic.RedactionBox { *; }
-keep class com.pdfchemy.app.logic.BatesConfig { *; }
-keep class com.pdfchemy.app.logic.StampConfig { *; }
-keep class com.pdfchemy.app.logic.HeaderFooterPosition { *; }
-keep class com.pdfchemy.app.logic.ImageAnalysis { *; }
-keep class com.pdfchemy.app.logic.ImageValidationStatus { *; }
-keep class com.pdfchemy.app.logic.PerceivedQualityLoss { *; }
-keep class com.pdfchemy.app.logic.ImageCompressionResult { *; }
-keep class com.pdfchemy.app.logic.TextMatchOccurrence { *; }
-keep class com.pdfchemy.app.logic.FindReplaceSummary { *; }
-keep class com.pdfchemy.app.logic.EmbeddedImageInfo { *; }

# Bouncy Castle Cryptographic Providers (PKI Digital Signatures)
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.jcajce.provider.asymmetric.util.**

# Google Play Billing Library
-keep class com.android.billingclient.api.** { *; }

# Note: Google Mobile Ads and UMP libraries package their own consumer ProGuard/R8 rules.
# Blanket keep rules for com.google.android.gms.ads and com.google.android.ump have been removed
# to allow R8 to perform proper dead-code elimination and resource shrinking on release builds.

# Bouncy Castle's optional LDAP certificate/CRL stores require desktop JNDI.
# PDFchemy signs with in-memory certificates and does not call these stores or
# network PKIX validation. R8 retains provider classes through the keep rules above.
-dontwarn javax.naming.**
