package com.dvil.retui.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.PackageInfoCompat

/**
 * Decides whether opening Keys shows first-install setup, the "what's new" page, or plain settings.
 * Someone who already has the keyboard turned on is an existing user and never gets setup pushed on them.
 */
internal object Onboarding {
    const val KEY_SETUP_DONE = "onboarding.setupDone"
    const val KEY_CHANGELOG_SEEN = "onboarding.changelogSeenVersion"
    const val KEY_UPDATE_NOTICED = "onboarding.updateNoticedVersion"
    const val KEY_SETUP_STARTED = "onboarding.setupStarted"

    enum class Surface { SETTINGS, SETUP, CHANGELOG }

    fun versionCode(context: Context): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun surfaceFor(
        setupDone: Boolean,
        keyboardEnabled: Boolean,
        currentVersion: Int,
        seenVersion: Int?,
        setupStarted: Boolean = false
    ): Surface = when {
        // An unfinished setup resumes even though its first step already turned the keyboard on.
        !setupDone && (!keyboardEnabled || setupStarted) -> Surface.SETUP
        seenVersion == null || currentVersion > seenVersion -> Surface.CHANGELOG
        else -> Surface.SETTINGS
    }

    fun surfaceFor(prefs: SharedPreferences, keyboardEnabled: Boolean, currentVersion: Int): Surface =
        surfaceFor(
            prefs.getBoolean(KEY_SETUP_DONE, false), keyboardEnabled, currentVersion, seenVersion(prefs),
            prefs.getBoolean(KEY_SETUP_STARTED, false)
        )

    fun seenVersion(prefs: SharedPreferences): Int? =
        if (prefs.contains(KEY_CHANGELOG_SEEN)) prefs.getInt(KEY_CHANGELOG_SEEN, 0) else null

    /** Finishing or skipping setup also counts as having read this version's notes. */
    fun markSetupDone(prefs: SharedPreferences, currentVersion: Int) {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).putInt(KEY_CHANGELOG_SEEN, currentVersion).apply()
    }

    fun markChangelogSeen(prefs: SharedPreferences, currentVersion: Int) {
        prefs.edit()
            .putBoolean(KEY_SETUP_DONE, true)
            .putInt(KEY_CHANGELOG_SEEN, maxOf(currentVersion, prefs.getInt(KEY_CHANGELOG_SEEN, 0)))
            .apply()
    }

    /** True once per update for an existing user, so the keyboard can point at the notes. */
    fun shouldNoticeUpdate(prefs: SharedPreferences, currentVersion: Int): Boolean {
        val seen = seenVersion(prefs)
        if (seen != null && currentVersion <= seen) return false
        // Someone in the middle of first-install setup is not an existing user getting an update.
        if (seen == null && prefs.getBoolean(KEY_SETUP_STARTED, false)) return false
        if (prefs.getInt(KEY_UPDATE_NOTICED, 0) >= currentVersion) return false
        prefs.edit().putInt(KEY_UPDATE_NOTICED, currentVersion).apply()
        return true
    }
}
