package net.hotiana.companion

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Locale

/** Standalone launcher using the same app discovery and sizing as the official Home app. */
class SourceHomeActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private var inventory = emptyList<HomeApp>()
    private var drawerOpen = false
    private var query = ""
    private var pendingWidget = AppWidgetManager.INVALID_APPWIDGET_ID
    private var activeGrid: GridLayout? = null
    private val drawerBack by lazy { OnBackInvokedCallback { closeDrawer() } }
    private var drawerBackRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("launcher", MODE_PRIVATE)
        widgetHost = AppWidgetHost(this, HOST_ID)
        widgetManager = AppWidgetManager.getInstance(this)
        pendingWidget = savedInstanceState?.getInt("pendingWidget", AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        drawerOpen = savedInstanceState?.getBoolean("drawer") == true
        query = savedInstanceState?.getString("query").orEmpty()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pendingWidget", pendingWidget)
        outState.putBoolean("drawer", drawerOpen)
        outState.putString("query", query)
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        // App roster may have changed while a system page or another app was open.
        if (drawerOpen) refreshGrid() else render()
    }

    override fun onStop() {
        widgetHost.stopListening()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (drawerOpen) closeDrawer() else render()
        }
    }

    @Deprecated("Back navigation on Android 12 and earlier")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && drawerBackRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(drawerBack)
        }
        super.onDestroy()
    }

    private fun closeDrawer() {
        drawerOpen = false
        query = ""
        render()
    }

    private fun render() {
        if (Build.VERSION.SDK_INT >= 33 && drawerOpen != drawerBackRegistered) {
            if (drawerOpen) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, drawerBack)
            } else {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(drawerBack)
            }
            drawerBackRegistered = drawerOpen
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(12) + bars.left, bars.top, dp(12) + bars.right, bars.bottom)
                insets
            }
        }
        val actions = LinearLayout(this)
        actions.addView(button(if (drawerOpen) "Home" else "Apps") {
            drawerOpen = !drawerOpen
            query = ""
            render()
        })
        actions.addView(button("Customise", ::customise))
        root.addView(actions)
        if (drawerOpen) root.addView(EditText(this).apply {
            hint = "Find an app"
            isSingleLine = true
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s.toString()
                    fillGrid()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!drawerOpen) {
            for (id in widgets()) {
                val info = widgetManager.getAppWidgetInfo(id) ?: continue
                val view = widgetHost.createView(this, id, info)
                val height = preferences.getInt("widget-height-$id", 180)
                body.addView(view, LinearLayout.LayoutParams(-1, dp(height)))
                body.addView(button("Edit ${info.loadLabel(packageManager)} widget") { editWidget(id, height) })
            }
        }
        activeGrid = GridLayout(this).apply {
            alignmentMode = GridLayout.ALIGN_BOUNDS
            isColumnOrderPreserved = true
        }
        body.addView(activeGrid)
        setContentView(root)
        root.requestApplyInsets()
        refreshGrid()
    }

    private fun refreshGrid() {
        inventory = homeApps(this)
        fillGrid()
    }

    private fun fillGrid() {
        val grid = activeGrid ?: return
        grid.removeAllViews()
        val config = resources.configuration
        val density = preferences.getInt("density", HomeGridDensity.STANDARD.step)
        val result = HomeGridResolver.resolve(HomeGridRequest(
            (config.screenWidthDp - 24).toFloat(), fontScale = config.fontScale, bold =
                config.fontWeightAdjustment > 0, gridStep = density))
        grid.columnCount = result.columns
        val pins = pins()
        val rows = if (drawerOpen) {
            val sought = query.trim().lowercase(Locale.ROOT)
            inventory.filter { it.label.lowercase(Locale.ROOT).contains(sought) }
        } else pins.mapNotNull { id -> inventory.firstOrNull { it.component == id } }
        if (rows.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = if (drawerOpen) "No matching apps" else "Hold an app in Apps to pin it here."
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }
        rows.forEach { app ->
            val tile = button(app.label) {
                if (!launchHomeApp(this, app)) notify("That app could not be opened.")
            }
            tile.gravity = Gravity.CENTER
            tile.maxLines = 2
            tile.setAllCaps(false)
            val art = homeAppIconSource(this, app)
            val icon = art?.let { source ->
                val badge = source.workBadge
                if (badge == null) source.base else android.graphics.drawable.LayerDrawable(arrayOf(source.base, badge)).apply {
                    setLayerSize(1, dp(16), dp(16))
                    setLayerGravity(1, Gravity.BOTTOM or Gravity.END)
                }
            }?.mutate()
            icon?.setBounds(0, 0, dp(38), dp(38))
            tile.setCompoundDrawables(null, icon, null, null)
            tile.setOnLongClickListener {
                val pinned = app.component in pins()
                val choices = if (pinned) arrayOf("Move earlier", "Move later", "Remove from Home", "App info")
                    else arrayOf("Pin to Home", "App info")
                AlertDialog.Builder(this).setTitle(app.label).setItems(choices) { _, index ->
                    when (choices[index]) {
                        "Pin to Home" -> savePins(pins() + app.component)
                        "Remove from Home" -> savePins(pins() - app.component)
                        "Move earlier" -> movePin(app.component, -1)
                        "Move later" -> movePin(app.component, 1)
                        "App info" -> if (!openHomeAppDetails(this, app)) notify("App info is unavailable.")
                    }
                }.show()
                true
            }
            grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = dp(90)
                setGravity(Gravity.FILL)
            })
        }
    }

    private fun customise() {
        val choices = arrayOf("Grid density", "Add widget", "Default Home settings")
        AlertDialog.Builder(this).setTitle("Customise").setItems(choices) { _, index ->
            when (index) {
                0 -> AlertDialog.Builder(this).setTitle("Grid density")
                    .setSingleChoiceItems(HomeGridDensity.entries.map { it.label }.toTypedArray(),
                        preferences.getInt("density", 1)) { dialog, value ->
                        preferences.edit().putInt("density", value).apply()
                        dialog.dismiss()
                        render()
                    }.show()
                1 -> chooseWidget()
                2 -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            }
        }.show()
    }

    private fun chooseWidget() {
        if (pendingWidget != AppWidgetManager.INVALID_APPWIDGET_ID) return
        val providers = widgetManager.installedProviders.sortedBy { it.loadLabel(packageManager).lowercase(Locale.ROOT) }
        if (providers.isEmpty()) return notify("No widgets are installed.")
        AlertDialog.Builder(this).setTitle("Add widget")
            .setItems(providers.map { it.loadLabel(packageManager) }.toTypedArray()) { _, index ->
                beginWidget(providers[index])
            }.show()
    }

    private fun beginWidget(provider: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id
        if (widgetManager.bindAppWidgetIdIfAllowed(id, provider.provider)) {
            configureWidget(id)
            return
        }
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider), BIND_WIDGET)
        } catch (_: ActivityNotFoundException) {
            discardWidget()
            notify("Android widget approval is unavailable.")
        }
    }

    private fun configureWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) {
            discardWidget()
        } else if (info.configure == null) {
            keepWidget(id)
        } else {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, CONFIGURE_WIDGET, null)
            } catch (_: ActivityNotFoundException) {
                discardWidget()
                notify("This widget's setup is unavailable.")
            }
        }
    }

    @Deprecated("Required for Android's widget configuration result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != BIND_WIDGET && requestCode != CONFIGURE_WIDGET) return
        val id = pendingWidget
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val returned = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) ?: id
        if (resultCode != RESULT_OK || returned != id) {
            discardWidget()
        } else if (requestCode == BIND_WIDGET) {
            configureWidget(id)
        } else {
            keepWidget(id)
        }
    }

    private fun keepWidget(id: Int) {
        if (widgetManager.getAppWidgetInfo(id) == null) {
            discardWidget()
            return
        }
        pendingWidget = AppWidgetManager.INVALID_APPWIDGET_ID
        saveWidgets(widgets() + id)
    }

    private fun discardWidget() {
        val id = pendingWidget
        pendingWidget = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(id)
    }

    private fun editWidget(id: Int, height: Int) {
        val sizes = listOf(120, 180, 240, 320)
        val choices = sizes.map { "$it dp tall" } + "Remove widget"
        AlertDialog.Builder(this).setTitle("Widget size").setItems(choices.toTypedArray()) { _, index ->
            if (index < sizes.size) {
                val requested = sizes[index]
                if (requested != height) {
                    preferences.edit().putInt("widget-height-$id", requested).apply()
                    render()
                }
            } else {
                widgetHost.deleteAppWidgetId(id)
                preferences.edit().remove("widget-height-$id").apply()
                saveWidgets(widgets() - id)
            }
        }.show()
    }

    private fun readArray(key: String): JSONArray =
        runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrElse { JSONArray() }

    private fun pins(): List<String> = readArray("pins").let { array ->
        List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
    }

    private fun widgets(): List<Int> = readArray("widgets").let { array ->
        List(array.length()) { array.optInt(it, AppWidgetManager.INVALID_APPWIDGET_ID) }
            .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
    }

    private fun movePin(id: String, delta: Int) {
        val positions = pins().toMutableList()
        val from = positions.indexOf(id)
        val to = from + delta
        if (from in positions.indices && to in positions.indices) {
            positions.add(to, positions.removeAt(from))
            savePins(positions)
        }
    }

    private fun savePins(pins: List<String>) {
        preferences.edit().putString("pins", JSONArray(pins.distinct()).toString()).apply()
        fillGrid()
    }

    private fun saveWidgets(widgets: List<Int>) {
        preferences.edit().putString("widgets", JSONArray(widgets).toString()).apply()
        render()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = ViewGroup.LayoutParams(-2, -2)
        setOnClickListener { action() }
    }

    private fun notify(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(size: Int) = (size * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val HOST_ID = 114
        private const val BIND_WIDGET = 10
        private const val CONFIGURE_WIDGET = 11
    }
}
