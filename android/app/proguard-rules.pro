# Apple's SDK includes JNI entry points and dynamically loaded playback classes.
-keep class com.apple.android.** { *; }
-keep interface com.apple.android.** { *; }
# Optional desktop code-generation/logging classes bundled in Apple's JavaCPP runtime.
-dontwarn org.apache.maven.plugins.annotations.Mojo
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
