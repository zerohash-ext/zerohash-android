# Zerohash SDK ProGuard Rules
# The library ships un-minified; these rules document the public API surface and
# the WebView bridge surface.

# Keep public API
-keep public class com.zerohash.sdk.ZerohashSDK { *; }
-keep public class com.zerohash.sdk.ZerohashSDKTypes** { *; }
-keep public class com.zerohash.sdk.ZerohashAllowList { *; }
-keep public class com.zerohash.sdk.fund.FundTypes** { *; }
-keep public interface com.zerohash.sdk.** { *; }

# No @JavascriptInterface rule: the bridge is registered through
# WebViewCompat.addWebMessageListener, which resolves the handler in Kotlin rather
# than by injected member name, so nothing here needs keeping for JS to reach.

# Keep WebView callbacks for the SDK's own clients only.
-keepclassmembers class com.zerohash.sdk.** extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class com.zerohash.sdk.** extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# Suppress R8 warning for JDK 9+ string concatenation class not present in the
# Android SDK.
-dontwarn java.lang.invoke.StringConcatFactory

# Preserve annotations and generic type signatures (needed for reflection)
-keepattributes Signature
-keepattributes *Annotation*

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
