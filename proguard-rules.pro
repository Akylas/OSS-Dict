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

# Keep descriptor JSON fields stable for persisted data compatibility
-keepclassmembers class itkach.aard2.descriptor.BaseDescriptor {
    java.lang.String id;
    long createdAt;
    long lastAccess;
}

-keepclassmembers class itkach.aard2.descriptor.BlobDescriptor {
    java.lang.String slobId;
    java.lang.String slobUri;
    java.lang.String blobId;
    java.lang.String key;
    java.lang.String fragment;
}

-keepclassmembers class itkach.aard2.descriptor.SlobDescriptor {
    java.lang.String format;
    java.lang.String path;
    java.lang.String mddPath;
    java.util.Map tags;
    boolean active;
    long priority;
    long blobCount;
    java.lang.String error;
    boolean expandDetail;
}

-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

