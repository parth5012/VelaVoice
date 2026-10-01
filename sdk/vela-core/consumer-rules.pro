# Keep native methods across dependencies
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep public SDK API for vela-core (scoped to core packages only, not recursive)
-keep public class com.velavoice.sdk.* {
    public protected *;
}

-keep public class com.velavoice.sdk.audio.* {
    public protected *;
}
