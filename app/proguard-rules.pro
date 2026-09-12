# Keep Javascript interface methods used by WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
