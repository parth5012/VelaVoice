# Keep custom Android Views in vela-voice-ui for XML inflation and programmatic creation
-keep public class com.velavoice.sdk.ui.* extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep public SDK API for vela-voice-ui
-keep public class com.velavoice.sdk.ui.* {
    public protected *;
}
