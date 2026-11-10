-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.xyon.empleados.**$$serializer { *; }
-keepclassmembers class com.xyon.empleados.** { *** Companion; }
-keepclasseswithmembers class com.xyon.empleados.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# WorkManager instantiates this worker by reflection (no HiltWorkerFactory).
-keep class com.xyon.empleados.ui.ReminderSyncWorker { *; }
# Strip all logging in release
-assumenosideeffects class android.util.Log { public static *** v(...); public static *** d(...); public static *** i(...); public static *** w(...); public static *** e(...); }
# LiquidBar: AGSL shaders and RenderEffect paths must survive R8
-keep class io.github.mkandreum.liquidbar.** { *; }
