package net.hotiana.companion

internal const val HOME_ECOSYSTEM_MAIN_ACTIVITY = "net.hotiana.companion.MainActivity"

/**
 * Keep normal installed-app discovery, including every other package's work-profile entries.
 * Our own package contributes only its actual launchable MainActivity in the current main
 * profile. No Home alias or invented component can become a pin through this exception.
 */
internal fun homeEcosystemLauncherEntryAllowed(
    packageName: String,
    activityName: String,
    localPackageName: String,
    currentMainProfile: Boolean,
): Boolean = packageName != localPackageName ||
    (currentMainProfile && activityName == HOME_ECOSYSTEM_MAIN_ACTIVITY)
