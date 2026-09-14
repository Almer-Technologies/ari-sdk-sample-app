package com.example.aridemo

import com.ari_os.ari.sdk.AriToolProviderService
import com.ari_os.ari.sdk.declaration.AriToolRegistry

/**
 * The runtime half of the integration: what Ari binds to when it invokes a tool.
 *
 * All this class does is name the declarations. [DemoTools] holds the tools and
 * their handlers, and the build reads that same object to write
 * `assets/ari_tools.json`, so what Ari is offered and what it reaches are one
 * thing by construction. The manifest publishes this service; nothing else here
 * is integration code.
 */
class AriToolService : AriToolProviderService() {

    override fun tools(): AriToolRegistry = DemoTools.registry
}
