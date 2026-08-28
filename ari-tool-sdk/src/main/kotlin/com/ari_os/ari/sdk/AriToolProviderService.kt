package com.ari_os.ari.sdk

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Base service a provider app extends to expose tools to Ari.
 *
 * Subclasses implement [onInvoke] only. The AIDL surface, JSON encoding, and
 * request correlation are handled here.
 *
 * Declare the subclass in your manifest as:
 * ```xml
 * <service android:name=".AriToolService" android:exported="true"
 *          android:permission="com.ari_os.ari.permission.BIND_TOOL_PROVIDER">
 *     <intent-filter>
 *         <action android:name="com.ari_os.ari.action.TOOL_PROVIDER"/>
 *     </intent-filter>
 *     <meta-data android:name="com.ari_os.ari.tools"
 *                android:resource="@xml/ari_tools"/>
 * </service>
 * ```
 *
 * Known limitation: if the service is destroyed before a queued invocation
 * starts, no callback ever fires for that request, and Ari's own client-side
 * timeout — not a callback — is what resolves it.
 */
abstract class AriToolProviderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run one tool.
     *
     * Called on the main dispatcher, so it is safe to touch UI state directly.
     * Throwing is safe — it is converted into an error result.
     *
     * @param tool Declared tool name.
     * @param args Validated arguments as declared in `res/xml/ari_tools`.
     * @return The outcome to hand back to Ari.
     */
    abstract suspend fun onInvoke(tool: String, args: JsonObject): AriToolResult

    /**
     * Optionally declare tools at runtime instead of via the manifest resource.
     *
     * Only consulted when the manifest sets
     * `com.ari_os.ari.tools.dynamic` to `true`.
     *
     * @return Tools to expose, or `null` to use the manifest resource.
     */
    open fun onListTools(): List<AriToolDeclaration>? = null

    private val binder = object : IAriToolProvider.Stub() {

        override fun getApiVersion(): Int = AriToolsContract.AIDL_VERSION

        override fun listTools(): String? = onListTools()?.let { tools ->
            json.encodeToString(ListSerializer(AriToolDeclaration.serializer()), tools)
        }

        override fun invoke(
            requestId: String,
            toolName: String,
            argsJson: String,
            callback: IAriToolCallback,
        ) {
            scope.launch {
                val result = try {
                    val args = if (argsJson.isBlank()) {
                        JsonObject(emptyMap())
                    } else {
                        json.parseToJsonElement(argsJson).jsonObject
                    }
                    onInvoke(toolName, args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AriToolResult.error(e.message ?: "provider failed")
                }
                try {
                    callback.onResult(requestId, result.toJson())
                } catch (ignored: RemoteException) {
                    // Ari is gone (dead binder); nothing to deliver to.
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
