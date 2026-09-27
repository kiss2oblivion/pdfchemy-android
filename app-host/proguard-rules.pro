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


