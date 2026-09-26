# UFI-TOOLS Client 混淆规则
# OkHttp / Gson 默认无需特殊规则，保留即可。
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.ufitools.client.model.** { *; }
