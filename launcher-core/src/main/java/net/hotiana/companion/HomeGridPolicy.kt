package net.hotiana.companion

import kotlin.math.floor
import kotlin.math.roundToInt

internal const val HOME_WIDGET_GUTTER_DP = 16f
internal const val HOME_MAX_BOARD_COLUMNS = 32
internal const val HOME_LABEL_SIDE_PADDING_DP = 4f

/**
 * The last-resort label scale when a full word would overflow its tile. At large system
 * text sizes the floor keeps long app names usable without shrinking below 60 percent.
 */
internal fun homeLabelFitFloor(fontScale: Float): Float = (.75f / fontScale.coerceAtLeast(.5f)).coerceIn(.6f, .75f)

/**
 * Sizing policy for the Home board and app grid. Computes the grid from the usable
 * window size, text scaling, and selected density.
 */
internal enum class HomeGridDensity(val wire: String, val label: String, val step: Int) {
    COMFORTABLE("comfortable", "Comfortable", 0),
    STANDARD("standard", "Standard", 1),
    DENSE("dense", "Dense", 2);

    companion object {
        /** The stored grid step's density; Large and Extra large tiles (3, 4) are tile sizes, not a density. */
        fun ofStep(step: Int): HomeGridDensity? = entries.firstOrNull { it.step == step }
        fun ofWire(wire: String): HomeGridDensity? = entries.firstOrNull { it.wire == wire.trim().lowercase() }
    }
}

/**
 * Text tiers: T1 <= 1.05, T2 <= 1.2, T3 <= 1.5, T4 above; bold text adds one
 * tier. [cellScale] is how much a density's target cell grows: tile captions are chrome, capped near 1.15, so the
 * grid grows gently while body text keeps the person's full size.
 */
internal enum class HomeTextTier(val cellScale: Float) {
    T1(1f), T2(1.05f), T3(1.10f), T4(1.15f);

    val large: Boolean get() = this >= T3
}

internal fun homeTextTier(fontScale: Float, bold: Boolean): HomeTextTier {
    val scale = if (fontScale.isFinite()) fontScale else 1f
    val base = when {
        scale <= 1.05f -> 0
        scale <= 1.2f -> 1
        scale <= 1.5f -> 2
        else -> 3
    }
    return HomeTextTier.entries[(base + if (bold) 1 else 0).coerceAtMost(3)]
}

/** The sizes the resolver works from. [HomeGridDefaultTokens] is the default implementation; another can be assigned. */
internal interface HomeGridTokens {
    val gutterDp: Float
    val tapTargetDp: Float
    /** Smallest cell (icon housing) a step accepts at [tier]. */
    fun targetCellDp(step: Int, tier: HomeTextTier): Float
    /** At most this many columns for [step]; wider windows give the rest to side-by-side widgets and margins. */
    fun maxColumns(usableWidthDp: Float, step: Int): Int
    /** Compact windows keep at least this many columns while a cell still meets its hard minimums. */
    fun floorColumns(usableWidthDp: Float, step: Int): Int
    /** Never a wider housing than this: extra width becomes margin. */
    fun maxCellDp(usableWidthDp: Float): Float
    fun rowPitchDp(usableWidthDp: Float, tier: HomeTextTier, step: Int): Float
}

/**
 * Default tokens. Targets are a ~96 / ~82 / ~68 dp column pitch at 1.0x text scale (a cell plus the 16 dp
 * gutter): Comfortable 80, Standard 66, Dense 52 dp cells. Large and Extra large tiles use 112 / 160 dp cells.
 * Window classes are XS < 380, S < 600, M < 840 and L, measured on the usable width (window less
 * Home's 2 x 16 dp side padding).
 */
internal object HomeGridDefaultTokens : HomeGridTokens {
    override val gutterDp: Float get() = HOME_WIDGET_GUTTER_DP
    override val tapTargetDp: Float = 48f
    /**
     * Comfortable and Standard grow with the text tier. Dense does not: it is the person asking for more apps per
     * row, so only the hard minimums (tap target, labels whole after the shrink step) take a column away.
     */
    override fun targetCellDp(step: Int, tier: HomeTextTier): Float = when (step) {
        0 -> 80f * tier.cellScale
        2 -> 52f
        3 -> 112f * tier.cellScale
        4 -> 160f * tier.cellScale
        else -> 66f * tier.cellScale
    }
    /**
     * Window class cap (compact 6, medium 10, expanded 12); Comfortable keeps two thirds, Standard five sixths.
     * Standard stops at four columns on a compact window and at eight on an expanded window, so
     * a foldable gets one consistent default grid on its cover and open screens. Dense uses the full window-class cap.
     */
    override fun maxColumns(usableWidthDp: Float, step: Int): Int {
        val compact = usableWidthDp < COMPACT_USABLE_DP
        val expanded = usableWidthDp >= EXPANDED_USABLE_DP
        val base = when {
            compact -> 6
            !expanded -> 10
            else -> 12
        }
        val standard = if (compact) 4 else if (expanded) 8 else (base * 5 + 3) / 6
        return when (step) {
            0 -> (base * 2 + 1) / 3
            1 -> standard
            // On a wide board the Standard cap must not make Large choose the same tiles.
            3 -> standard - 1
            else -> base
        }
    }
    /** XS and S keep four columns at every text tier (Comfortable and Standard alike). */
    override fun floorColumns(usableWidthDp: Float, step: Int): Int =
        if (step in 0..2 && usableWidthDp < COMPACT_USABLE_DP) 4 else 1
    override fun maxCellDp(usableWidthDp: Float): Float = 128f
    override fun rowPitchDp(usableWidthDp: Float, tier: HomeTextTier, step: Int): Float {
        // One row pitch on every window: a 104 dp cell plus the 16 dp gutter (a 360 dp phone keeps the 104 dp cell
        // with 56 dp icons); large text grows it.
        val base = (if (tier.large) 128f else 120f) + if (tier == HomeTextTier.T2) 4f else 0f
        val density = when (step) { 0 -> 8f; 2 -> -8f; 3 -> 24f; 4 -> 56f; else -> 0f }
        return base + density
    }

    /** 600 dp and 840 dp windows, less Home's 2 x 16 dp padding. */
    const val COMPACT_USABLE_DP = 568f
    const val EXPANDED_USABLE_DP = 808f
}

/**
 * What the grid is asked for. [gridStep] is the stored step (0 Comfortable, 1 Standard, 2 Dense, 3 Large tiles,
 * 4 Extra large tiles); [chosen] is false while the person has never picked one, so large text may raise Standard to
 * Comfortable for them. [preferredColumns] is an explicit count (for example an imported layout), still held to the minimums.
 */
internal data class HomeGridRequest(
    val widthDp: Float,
    val heightDp: Float = 0f,
    val fontScale: Float = 1f,
    val bold: Boolean = false,
    val gridStep: Int = HomeGridDensity.STANDARD.step,
    val chosen: Boolean = true,
    val preferredColumns: Int = 0,
    val labelsShown: Boolean = true,
)

internal data class HomeGridSpec(
    val columns: Int,
    val cellWidthDp: Float,
    val rows: Int,
    val rowPitchDp: Float,
    /** The step actually used: [HomeGridRequest.gridStep], or Comfortable chosen automatically for large text. */
    val step: Int,
    val tier: HomeTextTier,
    /** True when Comfortable was picked for the person because their text is large. */
    val automatic: Boolean,
    /** Share (0..1) of common app names whose words fit their label at this text size after the shrink step. */
    val labelFitShare: Float,
    /** Margin each side beyond Home's own padding, when [HomeGridTokens.maxCellDp] stops the cells growing. */
    val extraMarginDp: Float,
) {
    val density: HomeGridDensity? get() = HomeGridDensity.ofStep(step)
}

/**
 * Sample app names used only as text to estimate whether labels fit; the names are trademarks of their
 * respective owners and imply no affiliation. Multi-word names break between words, so a name fits when its
 * longest word fits.
 */
internal val HOME_GRID_COMMON_NAMES = listOf("Facebook", "Messages", "WhatsApp", "Messenger", "Instagram", "YouTube",
    "Settings", "Calculator", "Calendar", "Camera", "Gallery", "Contacts", "Phone", "Chrome", "Gmail", "Maps", "Drive",
    "Photos", "Play Store", "Galaxy Store", "Samsung Health", "Samsung Wallet", "SmartThings", "Spotify", "Netflix",
    "Telegram", "Snapchat", "TikTok", "Weather", "Clock", "My Files", "Google", "Microsoft Teams")

/** Share of [HOME_GRID_COMMON_NAMES] kept whole: at most this many may need the whole-word ellipsis. */
internal const val HOME_GRID_LABEL_SHARE = .9f

/**
 * Estimated share of [names] whose longest word fits a label [cellWidthDp] + gutter wide (the label's width)
 * at the caption's 12 sp, the person's [fontScale] and the label's own last shrink step ([homeLabelFitFloor]).
 * An average advance of .56 em (.60 em bold) is a deliberate over-estimate of the bundled sans faces, so this never
 * keeps a column a real label could not show.
 */
internal fun homeGridLabelFitShare(cellWidthDp: Float, fontScale: Float, bold: Boolean,
    gutterDp: Float = HOME_WIDGET_GUTTER_DP, names: List<String> = HOME_GRID_COMMON_NAMES): Float {
    if (names.isEmpty()) return 1f
    val scale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val sp = 12f * scale * homeLabelFitFloor(scale)
    val advance = sp * if (bold) .60f else .56f
    // The words get the label's width less its clear room on both sides.
    val box = cellWidthDp + gutterDp - 2f * HOME_LABEL_SIDE_PADDING_DP
    return names.count { name -> name.split(' ').maxOf { it.length } * advance <= box }.toFloat() / names.size
}

internal object HomeGridResolver {
    /** The tokens in use. A different implementation can be assigned here. */
    @Volatile var tokens: HomeGridTokens = HomeGridDefaultTokens

    fun cellWidth(widthDp: Float, columns: Int, gutterDp: Float = tokens.gutterDp): Float {
        val count = columns.coerceAtLeast(1)
        return ((widthDp.coerceAtLeast(1f) - gutterDp * (count - 1)) / count).coerceAtLeast(1f)
    }

    fun resolve(request: HomeGridRequest, tokens: HomeGridTokens = this.tokens): HomeGridSpec {
        val width = request.widthDp.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val tier = homeTextTier(request.fontScale, request.bold)
        val stored = request.gridStep.coerceIn(0, 4)
        val automatic = !request.chosen && stored == HomeGridDensity.STANDARD.step && tier.large
        val step = if (automatic) HomeGridDensity.COMFORTABLE.step else stored
        val cap = tokens.maxColumns(width, step).coerceIn(1, HOME_MAX_BOARD_COLUMNS)
        val gutter = tokens.gutterDp
        fun cell(n: Int) = cellWidth(width, n, gutter)
        fun labelShare(n: Int) = homeGridLabelFitShare(cell(n), request.fontScale, request.bold, gutter)
        // Hard minimums: a full tap target and, while labels show, common names whole after the shrink step.
        fun allowed(n: Int) = cell(n) >= tokens.tapTargetDp &&
            (!request.labelsShown || labelShare(n) >= HOME_GRID_LABEL_SHARE)
        // An explicit count (for example an imported layout) is held to the hard minimums only, not to the density's cap.
        val hardMax = ((if (request.preferredColumns > 0) HOME_MAX_BOARD_COLUMNS else cap) downTo 1).firstOrNull(::allowed) ?: 1
        val target = tokens.targetCellDp(step, tier)
        val byTarget = (cap downTo 1).firstOrNull { cell(it) >= target } ?: 1
        val wanted = if (request.preferredColumns > 0) request.preferredColumns
            else maxOf(byTarget, tokens.floorColumns(width, step))
        val columns = wanted.coerceIn(1, hardMax)
        val cellWidth = cell(columns)
        val standardCellCap = tokens.maxCellDp(width)
        val maxCell = when (step) {
            3 -> maxOf(standardCellCap, 144f)
            4 -> maxOf(standardCellCap, 176f)
            else -> standardCellCap
        }
        val extraMargin = if (columns >= cap && cellWidth > maxCell)
            ((width - (columns * maxCell + gutter * (columns - 1))) / 2f).coerceAtLeast(0f) else 0f
        val pitch = tokens.rowPitchDp(width, tier, step)
        val rows = if (request.heightDp > 0f && request.heightDp.isFinite())
            floor((request.heightDp + gutter) / pitch).toInt().coerceAtLeast(1) else 0
        return HomeGridSpec(columns, cellWidth, rows, pitch, step, tier, automatic, labelShare(columns), extraMargin)
    }
}

/**
 * The count Home draws: the resolver's, except that an arranged Home saved at the earlier column count ([legacy])
 * keeps it while the person has not picked a grid and is not previewing one. An update never moves a saved Home;
 * picking a grid moves to the resolver's count, and the board saved at [legacy] stays stored for a return to it.
 */
internal fun homeBoardDrawnColumns(resolved: Int, legacy: Int, chosen: Boolean, previewing: Boolean,
    savedAtLegacy: () -> Boolean): Int =
    if (!previewing && !chosen && legacy != resolved && savedAtLegacy()) legacy else resolved

/**
 * The column count is the grid resolver's ([HomeGridResolver]), computed from the usable width, the text tier
 * and the step's density, never a fixed count. This convenience signature assumes no bold text and the step as
 * chosen; callers that know the bold setting and whether the person chose a grid should build a [HomeGridRequest].
 */
internal fun homeBoardColumns(innerWidthDp:Float,fontScale:Float,iconStep:Int,preferredColumns:Int=0): Int =
    HomeGridResolver.resolve(HomeGridRequest(innerWidthDp, fontScale = fontScale, gridStep = iconStep,
        preferredColumns = preferredColumns.coerceIn(0, HOME_MAX_BOARD_COLUMNS))).columns

/**
 * The earlier column count: even counts from a 60 dp target (72 Roomy, 48 Compact). Kept for boards already saved
 * at it, so a board arranged under this count can keep it until the person picks a grid.
 */
internal fun homeLegacyBoardColumns(innerWidthDp:Float,fontScale:Float,iconStep:Int,preferredColumns:Int=0): Int {
    val width=innerWidthDp.coerceAtLeast(1f)
    val target=when(iconStep) { 0->72f; 2->48f; 3->112f; 4->160f; else->60f }*fontScale.coerceIn(1f,1.6f)
    val proposed=(((width+HOME_WIDGET_GUTTER_DP)/(target+HOME_WIDGET_GUTTER_DP)/2f).roundToInt()*2).coerceIn(2,HOME_MAX_BOARD_COLUMNS)
    val touchFit=((width+HOME_WIDGET_GUTTER_DP)/(48f+HOME_WIDGET_GUTTER_DP)).toInt().coerceAtLeast(1)
    return (if (preferredColumns > 0) preferredColumns.coerceIn(1, HOME_MAX_BOARD_COLUMNS) else proposed).coerceAtMost(touchFit)
}
