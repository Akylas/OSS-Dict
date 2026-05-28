# Proguard configuration for Jackson 2.x
-keep @com.fasterxml.jackson.annotation.JsonIgnoreProperties class * { *; }
-keep class com.fasterxml.** { *; }
-keep class org.codehaus.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-keepclassmembers public final enum com.fasterxml.jackson.annotation.JsonAutoDetect$Visibility {
    public static final com.fasterxml.jackson.annotation.JsonAutoDetect$Visibility *;
}

# General
-keepattributes SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,Signature,Exceptions,InnerClasses
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Proguard configuration for Speex
#-dontwarn javax.sound.sampled.AudioFileFormat$Type
#-dontwarn javax.sound.sampled.AudioFormat$Encoding
#-dontwarn javax.sound.sampled.AudioFormat
#-dontwarn javax.sound.sampled.spi.AudioFileReader
#-dontwarn javax.sound.sampled.spi.AudioFileWriter
#-dontwarn javax.sound.sampled.spi.FormatConversionProvider
