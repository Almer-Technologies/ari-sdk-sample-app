# Android instantiates a provider's service by the name in its manifest, and
# Ari binds it by action. The manifest keep rule aapt generates covers the
# subclass a partner declares; this rule also covers one a library contributes.
-keep public class * extends com.ari_os.ari.sdk.AriToolProviderService {
    public <init>();
}
