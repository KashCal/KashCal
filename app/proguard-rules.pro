# ============================================================================
# KashCal ProGuard/R8 Rules
# Comprehensive rules for Android + Compose + Room + Hilt + CalDAV
# ============================================================================

# ----------------------------------------------------------------------------
# General Android & Kotlin
# ----------------------------------------------------------------------------

# Keep source file names and line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# Keep Kotlin classes with @Keep annotation
-keep @androidx.annotation.Keep class * { *; }
-keep class kotlin.Metadata { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.flow.**

# ----------------------------------------------------------------------------
# Jetpack Compose
# ----------------------------------------------------------------------------
# Compose libraries ship their own consumer ProGuard rules (kept lambdas,
# stability annotations, runtime internals). Let R8 optimize the rest.

# Compose UI tooling (debug only, but keep to avoid warnings)
-dontwarn androidx.compose.ui.tooling.**

# ----------------------------------------------------------------------------
# Room Database
# ----------------------------------------------------------------------------

# Keep all Room entities
-keep class org.onekash.kashcal.data.db.entity.** { *; }

# Keep all Room DAOs
-keep class org.onekash.kashcal.data.db.dao.** { *; }

# Keep Room database class
-keep class org.onekash.kashcal.data.db.KashCalDatabase { *; }
-keep class org.onekash.kashcal.data.db.KashCalDatabase_Impl { *; }

# Keep Room converters
-keep class org.onekash.kashcal.data.db.converter.** { *; }

# Room generated code
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }

# ----------------------------------------------------------------------------
# Hilt / Dagger
# ----------------------------------------------------------------------------

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }

# Keep Hilt entry points
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep all @Inject annotated constructors
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# Keep all @Module classes
-keep @dagger.Module class * { *; }

# Keep all @Provides methods
-keepclassmembers class * {
    @dagger.Provides <methods>;
}

# Hilt ViewModel
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Hilt WorkManager
-keep class * extends androidx.work.ListenableWorker { *; }
-keep @androidx.hilt.work.HiltWorker class * { *; }

# KashCal DI modules
-keep class org.onekash.kashcal.di.** { *; }

# ----------------------------------------------------------------------------
# kotlinx.serialization (compiler plugin, no reflection — minimal rules needed)
# ----------------------------------------------------------------------------

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers @kotlinx.serialization.Serializable class org.onekash.kashcal.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ----------------------------------------------------------------------------
# OkHttp
# ----------------------------------------------------------------------------

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Keep OkHttp platform classes. OkHttp/Okio ship consumer rules; only the
# reflectively-loaded public-suffix DB name needs pinning here.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ----------------------------------------------------------------------------
# ical4j (RFC 5545 iCalendar parsing) - NUCLEAR OPTION
# ical4j 4.x uses heavy reflection and ServiceLoader, R8 breaks it
# ----------------------------------------------------------------------------

-dontwarn net.fortuna.ical4j.**
-dontwarn org.slf4j.**
-dontwarn javax.cache.**
-dontwarn groovy.**
-dontwarn org.joda.convert.**
-dontwarn org.threeten.extra.**

# NUCLEAR: Keep EVERYTHING in ical4j - no shrinking, no optimization, no obfuscation
-keep,includedescriptorclasses class net.fortuna.ical4j.** { *; }
-keep,includedescriptorclasses interface net.fortuna.ical4j.** { *; }
-keep,includedescriptorclasses enum net.fortuna.ical4j.** { *; }

# Keep all members with all access levels
-keepclassmembers class net.fortuna.ical4j.** {
    public *;
    protected *;
    private *;
    <init>(...);
    <fields>;
    <methods>;
}

# Prevent any modification to ical4j classes
-keepclasseswithmembers class net.fortuna.ical4j.** { *; }
-keepclasseswithmembernames class net.fortuna.ical4j.** { *; }

# Keep class hierarchy and generic signatures
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes RuntimeVisible*Annotations

# Keep ical4j service providers (loaded via ServiceLoader from META-INF/services)
-keep class * implements net.fortuna.ical4j.model.ComponentFactory { *; }
-keep class * implements net.fortuna.ical4j.model.PropertyFactory { *; }
-keep class * implements net.fortuna.ical4j.model.ParameterFactory { *; }
-keep class * implements net.fortuna.ical4j.validate.CalendarValidatorFactory { *; }
-keep class * implements net.fortuna.ical4j.transform.compliance.Rfc5545ComponentRule { *; }
-keep class * implements net.fortuna.ical4j.transform.compliance.Rfc5545PropertyRule { *; }

# Keep ical4j ZoneRulesProvider (loaded via ServiceLoader)
-keep class net.fortuna.ical4j.model.DefaultZoneRulesProvider { *; }
-keep class * implements java.time.zone.ZoneRulesProvider { *; }

# Ensure ServiceLoader can instantiate service providers
-keepclassmembers class * implements net.fortuna.ical4j.model.ComponentFactory {
    public <init>();
}
-keepclassmembers class * implements net.fortuna.ical4j.model.PropertyFactory {
    public <init>();
}
-keepclassmembers class * implements net.fortuna.ical4j.model.ParameterFactory {
    public <init>();
}

# ical4j dependencies that also need protection
-keep class org.cache2k.** { *; }
-dontwarn org.cache2k.**

# Remove JCacheTimeZoneCache, use MapTimeZoneCache instead (from k3b/calef)
-assumenosideeffects class net.fortuna.ical4j.util.JCacheTimeZoneCache
-assumenosideeffects class javax.cache.Cache
-assumenosideeffects class javax.cache.CacheManager
-assumenosideeffects class javax.cache.Caching
-assumenosideeffects class javax.cache.configuration.Configuration
-assumenosideeffects class javax.cache.configuration.MutableConfiguration
-assumenosideeffects class javax.cache.spi.CachingProvider
-keep class net.fortuna.ical4j.util.MapTimeZoneCache { *; }

# Groovy (unused in Android)
-dontwarn org.codehaus.groovy.**

# ----------------------------------------------------------------------------
# ez-vcard (RFC 2426 3.0 / RFC 6350 4.0 vCard parsing)
# ez-vcard instantiates its property/parameter classes and scribes by
# reflection (e.g. a (String,String,String) constructor on parameter classes
# like ImageType, and the TelUri parser), so R8 renaming or removing those
# constructors makes EVERY vCard fail to parse on release builds — with no
# failure on debug/unit tests, which run unminified. Symptom when this rule is
# missing: CardDavContactReader logs "NoSuchMethodException: <obfuscated>.<init>
# [String,String,String]" for every contact and the whole address book drops.
# Keep the reflective parse surface (properties, parameters, scribes, vinnie);
# let R8 drop the unused jCard/hCard writers and their heavy deps (freemarker).
# ----------------------------------------------------------------------------

# Keep the reflectively-touched parse surface: the property/parameter model
# classes (constructed by reflection — the (String,String,String) constructor on
# parameter classes like ImageType is what R8 was stripping) and the scribes that
# read/write them. The jCard (ezvcard.io.json) and hCard (ezvcard.io.html)
# serializers are deliberately NOT kept — CardDAV exchanges only text/vcard, so
# letting R8 drop them also frees their heavy transitive deps (freemarker ~1.3MB).
-keep,includedescriptorclasses class ezvcard.property.** { *; }
-keep,includedescriptorclasses class ezvcard.parameter.** { *; }
-keep,includedescriptorclasses class ezvcard.io.scribe.** { *; }
-keepclassmembers class ezvcard.** {
    <init>(...);
}
-keep enum ezvcard.** { *; }
-keep class ezvcard.util.** { *; }

# vinnie: the underlying vObject reader ez-vcard drives reflectively.
-keep class com.github.mangstadt.vinnie.** { *; }
-dontwarn com.github.mangstadt.vinnie.**

# ez-vcard's jCard/hCard serializers reference jackson/jsoup/freemarker, all
# excluded or unreachable (CardDAV exchanges only text/vcard). Silence the
# dangling references from any of those classes R8 does retain.
-dontwarn com.fasterxml.jackson.**
-dontwarn org.jsoup.**
-dontwarn freemarker.**

# ----------------------------------------------------------------------------
# icaldav-core subproject — keep reflective/Kotlin-metadata surfaces
# (iCal model classes parsed/generated dynamically; safer to keep until an
#  R8 shrink-test verifies which members can be optimized away)
# ----------------------------------------------------------------------------

-keep class org.onekash.icaldav.** { *; }
-keep interface org.onekash.icaldav.** { *; }

# ----------------------------------------------------------------------------
# WorkManager
# ----------------------------------------------------------------------------

-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# WorkManager ships consumer rules; keep only our worker entry points, which
# WorkManager instantiates reflectively by class name.
-keep class org.onekash.kashcal.sync.worker.** { *; }

# ----------------------------------------------------------------------------
# DataStore Preferences
# ----------------------------------------------------------------------------
# DataStore ships consumer rules; keep only the generated protobuf message
# fields it accesses reflectively.
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# KashCal DataStore
-keep class org.onekash.kashcal.data.preferences.** { *; }

# ----------------------------------------------------------------------------
# Security Crypto (EncryptedSharedPreferences)
# ----------------------------------------------------------------------------

# security-crypto is Tink-backed and resolves key managers reflectively
# (ServiceLoader / Class.forName), so R8 must not shrink or rename it. Keep it
# whole until a device-verified credential round-trip proves what can be freed.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# KashCal Auth
-keep class org.onekash.kashcal.data.auth.** { *; }

# KashCal Repository layer (v22.0.0)
-keep class org.onekash.kashcal.data.repository.** { *; }

# KashCal Credential management (v22.0.0)
-keep class org.onekash.kashcal.data.credential.** { *; }

# ----------------------------------------------------------------------------
# KashCal Application Classes
# ----------------------------------------------------------------------------

# Keep Application class
-keep class org.onekash.kashcal.KashCalApplication { *; }

# Keep all UI models
-keep class org.onekash.kashcal.ui.viewmodels.** { *; }
-keep class org.onekash.kashcal.ui.components.** { *; }

# Keep domain layer
-keep class org.onekash.kashcal.domain.** { *; }

# Keep sync layer models
-keep class org.onekash.kashcal.sync.** { *; }

# Keep network classes
-keep class org.onekash.kashcal.network.** { *; }

# ----------------------------------------------------------------------------
# Enum classes (prevent obfuscation of enum values)
# ----------------------------------------------------------------------------

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# KashCal enums
-keep enum org.onekash.kashcal.data.db.entity.SyncStatus { *; }

# ----------------------------------------------------------------------------
# Parcelable (for state saving)
# ----------------------------------------------------------------------------

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ----------------------------------------------------------------------------
# Serializable
# ----------------------------------------------------------------------------

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ----------------------------------------------------------------------------
# Suppress Warnings
# ----------------------------------------------------------------------------

-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ----------------------------------------------------------------------------
# Glance Widgets (HIGH PRIORITY)
# ----------------------------------------------------------------------------

# Glance ships consumer rules; keep our widget + receiver subclasses, which the
# platform instantiates reflectively from the manifest.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class org.onekash.kashcal.widget.** { *; }

# ----------------------------------------------------------------------------
# Error Handling (Sealed Classes)
# ----------------------------------------------------------------------------

-keep class org.onekash.kashcal.error.** { *; }

# ----------------------------------------------------------------------------
# ICS Subscription & Utilities
# ----------------------------------------------------------------------------

-keep class org.onekash.kashcal.data.ics.** { *; }
-keep class org.onekash.kashcal.util.** { *; }

# ----------------------------------------------------------------------------
# Additional Enums
# ----------------------------------------------------------------------------

-keep enum org.onekash.kashcal.data.db.entity.ReminderStatus { *; }