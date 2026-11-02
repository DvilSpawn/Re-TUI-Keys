package com.dvil.retui.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** Installs a pack zip from a content URI, whichever kind it is. Returns a message for the user. */
internal object PackImports {
    fun install(context: Context, prefs: SharedPreferences, uri: Uri): String {
        val isVoice = try {
            context.contentResolver.openInputStream(uri)?.use(VoicePackManager::isVoicePack) ?: false
        } catch (_: Exception) {
            false
        }
        return if (isVoice) installVoicePack(context, prefs, uri) else installLanguagePack(context, prefs, uri)
    }

    fun installVoicePack(context: Context, prefs: SharedPreferences, uri: Uri): String {
        val result = try {
            context.contentResolver.openInputStream(uri)?.use { VoicePackManager.install(context, it) }
        } catch (error: Exception) {
            VoicePackInstallResult(error = error.message)
        }
        val pack = result?.pack ?: return result?.error ?: "Voice pack import failed"
        VoiceEngines.select(prefs, VoiceEngines.Choice.Local(pack))
        return "Installed ${pack.name}"
    }

    private fun installLanguagePack(context: Context, prefs: SharedPreferences, uri: Uri): String {
        val result = try {
            context.contentResolver.openInputStream(uri)?.use { LanguagePackManager.install(context, it) }
        } catch (_: Exception) {
            null
        }
        val pack = result?.pack ?: return result?.error ?: "Pack import failed"
        LanguagePackManager.setActive(prefs, pack.id)
        return "Installed ${pack.nativeName}"
    }
}

/** Handles "Open with" / "Share to" on a downloaded language or voice pack. */
class PackImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.streamUri()
            else -> intent?.data
        }
        if (uri == null) {
            finish()
            return
        }
        val appContext = applicationContext
        val prefs = getSharedPreferences(KeyboardPrefs.PREFS_NAME, MODE_PRIVATE)
        Toast.makeText(appContext, "Installing pack…", Toast.LENGTH_SHORT).show()
        // The URI grant lasts while this activity is alive, so finish only after the copy is done.
        Thread {
            val message = PackImports.install(appContext, prefs, uri)
            runOnUiThread {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)
}
