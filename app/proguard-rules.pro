# ProGuard/R8 规则 — E-ink Reader
# 原则: 只保留确有反射调用的类, 其余交给 R8 混淆/裁剪

# ---- 保留调试堆栈信息, 便于线上崩溃还原 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Gson: 依赖反射的字段 ----
-keep class com.zeoffice.einkreader.updater.GitHubRelease { *; }
-keep class com.zeoffice.einkreader.data.db.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

# ---- Retrofit/OkHttp (自带 consumer 规则, 此处补充兜底) ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- WebDAV XML 解析 (javax.xml DOM 反射) ----
-dontwarn javax.xml.**

# ---- androidx.startup / Compose 运行时所需的 metadata ----
-keep class androidx.startup.** { *; }
