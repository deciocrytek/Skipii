# NewPipeExtractor runs YouTube's player JavaScript through Rhino.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.annotation.**
# Rhino's invokedynamic optimizer targets desktop Java (jdk.dynalink); on Android Rhino
# runs interpreted and never loads it.
-dontwarn jdk.dynalink.**

# NewPipeExtractor must not be shrunk or optimized: with R8 applied to it, YouTube audio
# streams came back EMPTY in release builds (video was fine), so playback silently fell back
# to the 360p combined stream while the label said 1080p (2026-10-04). Debug builds were
# unaffected, which is why it slipped through. Keep the whole extractor and its JSON/HTML
# parsers intact.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Crash reports (CrashLog, 0.5.109) are pasted into GitHub issues from users' phones: keep real class
# and method names and line numbers so a stack trace reads without a mapping file (the source is public).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
