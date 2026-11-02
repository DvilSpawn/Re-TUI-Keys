package com.dvil.retui.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.speech.RecognitionService

/**
 * Which speech recognizer voice typing uses. The stored value is empty for the system default,
 * `pack:<id>` for an installed [VoicePack], or a flattened [ComponentName] of a [RecognitionService].
 */
internal object VoiceEngines {
    const val KEY_VOICE_ENGINE = "voice.engine"
    private const val PACK_PREFIX = "pack:"

    data class Engine(val component: ComponentName, val label: String)

    sealed interface Choice {
        object SystemDefault : Choice
        data class Service(val engine: Engine) : Choice
        data class Local(val pack: VoicePack) : Choice
    }

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

    /** The chosen engine if it is still installed, otherwise the system default. */
    fun selected(context: Context, prefs: SharedPreferences): Choice {
        val stored = prefs.getString(KEY_VOICE_ENGINE, null)?.takeIf { it.isNotBlank() } ?: return Choice.SystemDefault
        if (stored.startsWith(PACK_PREFIX)) {
            return VoicePackManager.find(context, stored.removePrefix(PACK_PREFIX))
                ?.let { Choice.Local(it) } ?: Choice.SystemDefault
        }
        val component = ComponentName.unflattenFromString(stored) ?: return Choice.SystemDefault
        return installed(context).firstOrNull { it.component == component }
            ?.let { Choice.Service(it) } ?: Choice.SystemDefault
    }

    fun select(prefs: SharedPreferences, choice: Choice) {
        val value = when (choice) {
            Choice.SystemDefault -> ""
            is Choice.Service -> choice.engine.component.flattenToString()
            is Choice.Local -> PACK_PREFIX + choice.pack.id
        }
        prefs.edit().putString(KEY_VOICE_ENGINE, value).apply()
    }

    fun label(choice: Choice): String = when (choice) {
        Choice.SystemDefault -> "System default"
        is Choice.Service -> choice.engine.label
        is Choice.Local -> choice.pack.name
    }
}
