# Android instantiates a provider's service by the name in its manifest, and
# Ari binds it by action. The manifest keep rule aapt generates covers the
# subclass a partner declares; this rule also covers one a library contributes.
-keep public class * extends com.ari_os.ari.sdk.AriToolProviderService {
    public <init>();
}

# Ari and a provider app share these generated classes across a process
# boundary. The framework calls onTransact from native code, so R8 sees no
# caller for the Stub and the Proxy. Keeping them whole also keeps a
# provider's binder classes readable in a crash report.
-keep class com.ari_os.ari.sdk.IAriToolProvider$Stub { *; }
-keep class com.ari_os.ari.sdk.IAriToolProvider$Stub$Proxy { *; }
-keep class com.ari_os.ari.sdk.IAriToolCallback$Stub { *; }
-keep class com.ari_os.ari.sdk.IAriToolCallback$Stub$Proxy { *; }
