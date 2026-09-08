package com.ari_os.ari.sdk

import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Ari did with the set sent by [AriTools.setAvailable]. */
sealed interface AriAvailabilityResult {

    /** Ari stored the set and hides every declared tool outside it. */
    data object Accepted : AriAvailabilityResult

    /** This device runs no Ari, so there is nothing to tell. */
    data object AriMissing : AriAvailabilityResult

    /** Ari refused the set, so sending the same set again fails again. */
    data class Rejected(val reason: String?) : AriAvailabilityResult

    /** The set did not reach Ari, so a later call can still work. */
    data class Failed(val reason: String?) : AriAvailabilityResult
}

/** What a provider app tells Ari outside a tool invocation. */
object AriTools {

    /** Replaces the whole set of declared tools this app can run right now. */
    suspend fun setAvailable(
        context: Context,
        names: Set<String>,
        registry: AriToolRegistry? = null,
    ): AriAvailabilityResult {
        requireAvailableNames(names, registry)
        return push(context, names)
    }

    private fun requireAvailableNames(names: Set<String>, registry: AriToolRegistry?) {
        require(names.size <= AriToolsContract.MAX_TOOLS_PER_PROVIDER) {
            "at most ${AriToolsContract.MAX_TOOLS_PER_PROVIDER} tools can be available, " +
                "and this set holds ${names.size}"
        }
        names.forEach { name -> requireDeclaredName("tool", name) }
        if (registry == null) return
        val declared = registry.declarations.map { tool -> tool.name }.toSet()
        val undeclared = names - declared
        require(undeclared.isEmpty()) {
            "this app declares no tool named ${undeclared.sorted().joinToString()}. " +
                "It declares ${declared.sorted().joinToString()}"
        }
    }

    private suspend fun push(context: Context, names: Set<String>): AriAvailabilityResult =
        withContext(Dispatchers.IO) {
            val extras = Bundle().apply {
                putStringArrayList(
                    AriToolsContract.KEY_AVAILABLE_TOOLS,
                    ArrayList(names.sorted()),
                )
            }
            val reply = try {
                context.contentResolver.call(
                    AriToolsContract.AUTHORITY_TOOLS,
                    AriToolsContract.METHOD_SET_AVAILABLE,
                    null,
                    extras,
                )
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "this device resolves no Ari tools provider", e)
                return@withContext AriAvailabilityResult.AriMissing
            } catch (e: SecurityException) {
                Log.w(TAG, "Ari refused the availability call", e)
                return@withContext AriAvailabilityResult.Rejected(e.message)
            } catch (e: RuntimeException) {
                Log.w(TAG, "the availability call did not reach Ari", e)
                return@withContext AriAvailabilityResult.Failed(e.message)
            }
            read(reply, names.size)
        }

    // Silence never counts as success: only an explicit accepted flag does.
    private fun read(reply: Bundle?, count: Int): AriAvailabilityResult {
        if (reply == null) {
            Log.w(TAG, "Ari answered nothing, so it may not hold the set")
            return AriAvailabilityResult.Failed("Ari sent no reply")
        }
        val reason = reply.getString(AriToolsContract.KEY_REASON)
        if (!reply.getBoolean(AriToolsContract.KEY_ACCEPTED)) {
            Log.w(TAG, "Ari refused the available set: $reason")
            return AriAvailabilityResult.Rejected(reason)
        }
        Log.i(TAG, "Ari accepted $count available tools")
        return AriAvailabilityResult.Accepted
    }

    // android.util.Log, not the repo-standard logging(): partner apps compile
    // against this module, so it takes no third-party logging dependency.
    private const val TAG = "AriTools"
}
