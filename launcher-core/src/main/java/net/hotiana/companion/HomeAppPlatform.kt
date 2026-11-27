package net.hotiana.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager

/**
 * A launchable app, possibly in a work profile.
 *
 * [component] is the identity saved layouts are keyed on. For the MAIN profile it stays
 * the bare flattened ComponentName, so layouts keyed on it need no migration. Only apps in
 * another profile carry a suffix: a raw Intent cannot start another user's activity, so
 * those apps are opened through LauncherApps instead.
 */
internal data class HomeApp(val component: String, val label: String) {
    val packageName get() = activity()?.packageName.orEmpty()

    /** The real component, with any profile suffix stripped. */
    fun activity(): ComponentName? =
        ComponentName.unflattenFromString(component.substringBefore(PROFILE_MARK))

    /** 0 for the main profile; the user serial number otherwise. */
    fun profileSerial(): Long = component.substringAfter(PROFILE_MARK, "").toLongOrNull() ?: 0L

    companion object {
        /** ComponentName.flattenToString cannot emit this, so the key cannot collide. */
        const val PROFILE_MARK = "|u"

        fun key(component: ComponentName, serial: Long): String =
            if (serial == 0L) component.flattenToString()
            else component.flattenToString() + PROFILE_MARK + serial
    }
}

/**
 * Every launchable activity in EVERY profile, without QUERY_ALL_PACKAGES.
 *
 * queryIntentActivities sees only the current user, and a raw Intent cannot start another
 * user's activity, so work-profile apps are listed and opened through LauncherApps.
 *
 * LauncherApps degrades rather than throwing when this app does not hold the home role, so
 * the fallback is the single-profile query, which is what a non-launcher install should see.
 */
internal fun homeApps(context: Context): List<HomeApp> {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
    val viaLauncher = runCatching {
        launcher?.profiles?.flatMap { user ->
            val serial = users?.getSerialNumberForUser(user) ?: 0L
            launcher.getActivityList(null, user)
                .filter { homeEcosystemLauncherEntryAllowed(
                    it.componentName.packageName, it.componentName.className, context.packageName,
                    currentMainProfile = serial == 0L && user == android.os.Process.myUserHandle(),
                ) }
                .map { HomeApp(HomeApp.key(it.componentName, serial), it.label.toString()) }
        }
    }.getOrNull()
    val apps = viaLauncher?.takeIf { it.isNotEmpty() }
        ?: context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo?.enabled == true &&
                homeEcosystemLauncherEntryAllowed(it.activityInfo.packageName, it.activityInfo.name,
                    context.packageName, currentMainProfile = true) }
            .map { HomeApp(ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(), it.loadLabel(context.packageManager).toString()) }
    return apps.distinctBy { it.component }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/**
 * Opens an app, in its own profile.
 *
 * startMainActivity is the ONLY route into another user's activity; the raw Intent below
 * cannot do it and silently does nothing for a work app. It also takes the bounds the
 * icon occupied and an ActivityOptions, which is how a third-party launcher gets its own
 * zoom-from-the-icon opening. [from] is the icon's bounds in window coordinates.
 */
internal fun launchHomeApp(
    context: Context,
    app: HomeApp,
    from: android.graphics.Rect? = null,
): Boolean {
    val component = app.activity() ?: return false
    val serial = app.profileSerial()
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
    val user = if (serial == 0L) android.os.Process.myUserHandle() else users?.getUserForSerialNumber(serial)
    val opening = openingAnimation(context, from)
    if (launcher != null && user != null) {
        val opened = runCatching {
            launcher.startMainActivity(component, user, from, opening)
            true
        }.getOrDefault(false)
        // Every successful launch is recorded for the "Suggested now" ranking (HomeLaunchHabits).
        if (opened) return true.also { HomeLaunchHabits.record(context, app.component) }
    }
    // Nothing below can start another user activity, so do not pretend to try.
    if (serial != 0L) return false
    return try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .apply { sourceBounds = from },
            opening,
        )
        HomeLaunchHabits.record(context, app.component)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Open Android's app information for the same profile the icon launches. */
internal fun openHomeAppDetails(context: Context, app: HomeApp): Boolean {
    val component = app.activity() ?: return false
    val serial = app.profileSerial()
    val user = if (serial == 0L) android.os.Process.myUserHandle()
        else context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(serial) ?: return false
    return runCatching {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return false
        launcher.startAppDetailsActivity(component, user, null, null)
        true
    }.getOrDefault(false)
}

/** Native base art and profile mark are separate, so theming cannot tint or erase the badge. */
internal data class HomeAppIconSource(val base: Drawable, val workBadge: Drawable? = null)

internal fun homeAppIconProfile(context: Context, app: HomeApp): UserHandle? {
    val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
        ?: return null
    // profileSerial's legacy display default is not authority for a malformed profile key.
    val serial = if (HomeApp.PROFILE_MARK in app.component)
        app.component.substringAfter(HomeApp.PROFILE_MARK).toLongOrNull()?.takeIf { it > 0 } ?: return null
        else 0L
    return runCatching {
        val user = if (serial == 0L) android.os.Process.myUserHandle()
            else users.getUserForSerialNumber(serial) ?: return@runCatching null
        user.takeIf { it in users.userProfiles }
    }.getOrNull()
}

private fun homeAppUnbadgedIcon(context: Context, component: ComponentName, user: UserHandle): Drawable? {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    if (launcher != null) {
        val viaLauncher = runCatching {
            launcher.getActivityList(component.packageName, user)
                .firstOrNull { it.componentName == component && it.user == user }
                ?.getIcon(0)
        }.getOrNull()
        if (viaLauncher != null) return viaLauncher
    }
    // Nothing below can reach another user, so do not pretend to try.
    if (user != android.os.Process.myUserHandle()) return null
    return runCatching { context.packageManager.getActivityIcon(component) }.getOrNull()
}

/** Compatibility callers receive the profile-correct, unbadged provider artwork. */
internal fun homeAppIconDrawable(context: Context, app: HomeApp): Drawable? {
    val component = app.activity() ?: return null
    val user = homeAppIconProfile(context, app) ?: return null
    return homeAppUnbadgedIcon(context, component, user)
}

internal fun homeAppIconSource(context: Context, app: HomeApp): HomeAppIconSource? {
    val component = app.activity() ?: return null
    val user = homeAppIconProfile(context, app) ?: return null
    val base = homeAppUnbadgedIcon(context, component, user) ?: return null
    val badge = homePlatformProfileBadge(context, user)
    // A work app must retain its real platform distinction; absence of a readable badge
    // cannot authorize a visually indistinguishable personal-profile fallback.
    if (user != android.os.Process.myUserHandle() && badge == null) return null
    return HomeAppIconSource(base, badge)
}

/** Android's own profile/admin marker, isolated from a wholly transparent base.
 * Shared by app and exact-shortcut artwork; callers keep it out of material/reflection. */
internal fun homePlatformProfileBadge(context: Context, user: UserHandle): Drawable? =
    runCatching {
        val pixels = (48f * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val transparent = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        var raster: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val art = BitmapDrawable(context.resources, transparent)
            val badged = context.packageManager.getUserBadgedDrawableForDensity(art, user, null,
                context.resources.displayMetrics.densityDpi)
            val rendered = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also { raster = it }
            badged.setBounds(0, 0, pixels, pixels)
            badged.draw(Canvas(rendered))
            homeAppIconBadgeCrop(rendered)?.also { cropped = it }?.let { BitmapDrawable(context.resources, it) }
        } finally {
            transparent.recycle()
            // createBitmap can return the input when all of its bounds are alpha-present.
            // That raster is the retained badge and must remain usable by the renderer.
            raster?.takeIf { it !== cropped }?.recycle()
        }
    }.getOrNull()

/** Crop only alpha-present platform badge pixels; the supplied base was wholly transparent. */
internal fun homeAppIconBadgeCrop(bitmap: Bitmap): Bitmap? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
    pixels.forEachIndexed { index, pixel ->
        if (pixel ushr 24 != 0) {
            val x = index % bitmap.width; val y = index / bitmap.width
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
    }
    return if (right < left || bottom < top) null
        else Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
}

/**
 * The app's own shortcuts -- "New message", "New incognito tab" -- for the hold menu.
 *
 * Shortcuts are a large part of what separates a launcher icon from a plain link to an app.
 * Only the current default home app may read them. Without the role the call throws, and an
 * empty list is the right answer: the hold menu then has no shortcuts section instead of an error.
 *
 * Dynamic and manifest shortcuts both, ranked the way the app itself ranks them, because
 * the app knows which of its own actions matter more than we do.
 */
internal fun homeAppShortcuts(context: Context, app: HomeApp): List<android.content.pm.ShortcutInfo> {
    val component = app.activity() ?: return emptyList()
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return emptyList()
    val users = context.getSystemService(Context.USER_SERVICE) as? UserManager
    val serial = app.profileSerial()
    val user = if (serial == 0L) android.os.Process.myUserHandle() else users?.getUserForSerialNumber(serial)
        ?: return emptyList()
    return runCatching {
        val query = LauncherApps.ShortcutQuery()
            .setPackage(component.packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        launcher.getShortcuts(query, user).orEmpty()
            .filter { it.isEnabled }
            .sortedBy { it.rank }
            .take(4)
    }.getOrDefault(emptyList())
}

/** Starts one of the app's own shortcuts, from the icon it was opened on. */
internal fun startAppShortcut(context: Context, shortcut: android.content.pm.ShortcutInfo, from: android.graphics.Rect? = null): Boolean {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return false
    return runCatching {
        launcher.startShortcut(shortcut, from, openingAnimation(context, from))
        true
    }.getOrDefault(false)
}

/**
 * The app grows out of the icon that was touched.
 *
 * Passing sourceBounds alone does not animate anything -- it only tells the opened app
 * where it came from. The transition is the ActivityOptions, and building one needs a
 * View to anchor to, which is why this reaches for the Activity's decor view: the offsets
 * are relative to that view, and the tile reported its rect in window coordinates, so the
 * two already agree with no conversion.
 *
 * Returns null when there is no rect or no Activity behind this Context, and null is the
 * right answer there -- the system's default transition is correct, and inventing a
 * rectangle would animate the app out of the wrong place, which reads worse than no
 * animation at all.
 */
private fun openingAnimation(context: Context, from: android.graphics.Rect?): android.os.Bundle? {
    val bounds = from?.takeIf { !it.isEmpty } ?: return null
    val decor = (context as? android.app.Activity)?.window?.decorView ?: return null
    return runCatching {
        android.app.ActivityOptions
            .makeScaleUpAnimation(decor, bounds.left, bounds.top, bounds.width(), bounds.height())
            .toBundle()
    }.getOrNull()
}

