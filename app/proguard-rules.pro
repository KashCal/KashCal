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

# Compose compiler generates lambdas that must not be obfuscated
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep Composable functions (lambdas)
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Compose state classes
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.animation.** { *; }

# Compose UI tooling (debug only, but keep to avoid warnings)
-dontwarn androidx.compose.ui.tooling.**

# Keep stability annotations
-keep @interface androidx.compose.runtime.Stable
-keep @interface androidx.compose.runtime.Immutable

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
# Gson
# ----------------------------------------------------------------------------

-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep fields used for Gson serialization
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ----------------------------------------------------------------------------
# OkHttp
# ----------------------------------------------------------------------------

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Keep OkHttp platform classes
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# OkHttp Logging Interceptor
-keep class okhttp3.logging.** { *; }

# ----------------------------------------------------------------------------
# ical4j (RFC 5545 iCalendar parsing)
# ----------------------------------------------------------------------------

-dontwarn net.fortuna.ical4j.**
-dontwarn org.slf4j.**
-dontwarn javax.cache.**
-dontwarn groovy.**

# Keep ical4j model classes
-keep class net.fortuna.ical4j.** { *; }
-keep interface net.fortuna.ical4j.** { *; }

# Keep service providers for ical4j
-keep class * implements net.fortuna.ical4j.model.** { *; }

# ----------------------------------------------------------------------------
# lib-recur (RFC 5545 RRULE expansion)
# ----------------------------------------------------------------------------

-dontwarn org.dmfs.**
-keep class org.dmfs.** { *; }
-keep interface org.dmfs.** { *; }

# ----------------------------------------------------------------------------
# WorkManager
# ----------------------------------------------------------------------------

-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep WorkManager configuration
-keep class androidx.work.** { *; }

# KashCal workers
-keep class org.onekash.kashcal.sync.worker.** { *; }

# ----------------------------------------------------------------------------
# DataStore Preferences
# ----------------------------------------------------------------------------

-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# KashCal DataStore
-keep class org.onekash.kashcal.data.preferences.** { *; }

# ----------------------------------------------------------------------------
# Security Crypto (EncryptedSharedPreferences)
# ----------------------------------------------------------------------------

-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# KashCal Auth
-keep class org.onekash.kashcal.data.auth.** { *; }

# ----------------------------------------------------------------------------
# Kotlinx Immutable Collections
# ----------------------------------------------------------------------------

-keep class kotlinx.collections.immutable.** { *; }

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

-keep class androidx.glance.** { *; }
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