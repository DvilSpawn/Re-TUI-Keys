package com.dvil.retui.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.speech.RecognitionService

/**
 * Which speech recognizer voice typing uses. The stored value is empty for the system default,
 * or a flattened [ComponentName] of an installed [RecognitionService].
 */
internal object VoiceEngines {
    const val KEY_VOICE_ENGINE = "voice.engine"

    data class Engine(val component: ComponentName, val label: String)

    fun installed(context: Context): List<Engine> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                if (service.packageName == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                    .ifEmpty { service.applicationInfo.loadLabel(pm).toString() }
                Engine(ComponentName(service.packageName, service.name), label)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** The chosen engine if it is still installed, or null to use the system default. */
    fun selected(context: Context, prefs: SharedPreferences): Engine? {
        val stored = prefs.getString(KEY_VOICE_ENGINE, null)?.takeIf { it.isNotBlank() } ?: return null
        val component = ComponentName.unflattenFromString(stored) ?: return null
        return installed(context).firstOrNull { it.component == component }
    }

    fun select(prefs: SharedPreferences, engine: Engine?) {
        prefs.edit().putString(KEY_VOICE_ENGINE, engine?.component?.flattenToString().orEmpty()).apply()
    }
}
