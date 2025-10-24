-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-dontwarn io.flutter.**

-keep class dev.kotelnikoff.shorts_hls_player.** { *; }

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Fix for R8 XmlResourceParser/XmlPullParser conflict
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.v1.** { *; }
-keep class android.content.res.XmlResourceParser
-keep class android.content.res.XmlResourceParser$* { *; }

# Additional XML parser rules
-keep class org.xmlpull.v1.XmlPullParser { *; }
-keep class org.xmlpull.v1.XmlPullParserException { *; }
-keep class org.xmlpull.v1.XmlSerializer { *; }

# Keep all XML related classes
-keep class * implements org.xmlpull.v1.XmlPullParser { *; }
-keep class * implements org.xmlpull.v1.XmlSerializer { *; }
