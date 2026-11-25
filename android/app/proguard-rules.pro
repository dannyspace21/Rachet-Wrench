# proguard-rules.pro — rules for the release build's code shrinker (R8).
#
# R8 removes code that looks unused.  Some libraries create their parts BY
# NAME at run time (reflection) or are called from native code, which R8
# can't see — those parts must be kept intact or the app crashes when they
# are first used.

# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ratchetwrench.app.**$$serializer { *; }

# --- ML Kit (barcode + text recognition) ---------------------------------
# ML Kit creates its components from names listed in the manifest
# ("ComponentRegistrar" classes) by calling their no-argument constructor.
# R8's full mode removed those constructors, which crashed the app the
# moment the VIN barcode scanner (or the door-sticker reader) started.
# Its native libraries (libbarhopper, the OCR pipeline) also call back into
# these Java classes by exact name, so keep the whole of ML Kit intact.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-keep class com.google.photos.vision.** { *; }

# Don't warn about optional OkHttp platform integrations we don't use.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
