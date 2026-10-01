# Keep native methods and their declaring classes to preserve JNI symbol bindings
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep WhisperEngine and its native methods to prevent class/method renaming by R8
-keep class com.velavoice.sdk.whisper.WhisperEngine {
    native <methods>;
    public <init>(...);
    public <methods>;
}

# Keep public SDK API for vela-whisper
-keep public class com.velavoice.sdk.whisper.* {
    public protected *;
}
