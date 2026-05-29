# Jackson core
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Keep model classes used for serialization
-keep class itkach.aard2.** { *; }

# Keep enum methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep annotations/signatures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep constructors
-keepclassmembers class * {
    public <init>();
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

