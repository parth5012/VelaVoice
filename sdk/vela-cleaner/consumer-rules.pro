# Keep native methods and their declaring classes (preserves ONNX Runtime GenAI JNI)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep public SDK API for vela-cleaner
-keep public class com.velavoice.sdk.cleaner.* {
    public protected *;
}
