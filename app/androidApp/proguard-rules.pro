# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

-keep class com.dot.gallery.feature.editor.adjustments.** { *; }
-keep class com.drew.** { *; }
-keep class java.io.** { *; }
-keep class com.adobe.** { *; }
-keep class ai.onnxruntime.** { *; }

# kotlinx.parcelize.Parcelize is a BINARY-retention annotation whose class lives only in
# kotlin-parcelize-runtime (compile time). The @Parcelize codegen has already run, so no
# runtime code references it; without this R8 fails the release build with
# "Missing class kotlinx.parcelize.Parcelize (referenced from: ...FrameIdentity and 22 other contexts)".
-dontwarn kotlinx.parcelize.**

# Room builds its generated database implementation by name and instantiates it reflectively.
# R8 kept WorkDatabase_Impl but removed its no-arg constructor (nothing calls it statically),
# so the app died at startup with "Failed to create an instance of class
# androidx.work.impl.WorkDatabase". room-runtime's consumer rule only keeps the class name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Same story for WorkManager: InputMergerKt does Class.forName(name) +
# getDeclaredConstructor().newInstance(), and WorkManager stores the merger class
# name in each WorkRequest. R8 removed OverwritingInputMerger's constructor, so
# every queued job died in WM-InputMerger with NoSuchMethodException while
# VaultOperationWorker kept retrying and the vault stayed empty.
-keep class * extends androidx.work.InputMerger { <init>(); }

# ListenableWorker subclasses are also instantiated by name; keep all constructors
# (the work-runtime consumer rule only keeps public ones).
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options

# Keep custom Glide decoders and model loaders (HEIF/JXL/Encrypted)
-keep class com.dot.gallery.core.decoder.glide.** { *; }
-keep class com.radzivon.bartoshyk.avif.** { *; }
-keep class com.awxkee.jxlcoder.** { *; }
-dontwarn com.radzivon.bartoshyk.avif.**
-dontwarn com.awxkee.jxlcoder.**

# Cloud provider models (Retrofit DTOs, Room entities, serialization)
-keep class com.dot.gallery.cloud.** { *; }
-keep class com.dot.gallery.cloud.data.entity.** { *; }
-keep class com.dot.gallery.cloud.core.** { *; }
-keep class com.dot.gallery.cloud.image.** { *; }

-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid