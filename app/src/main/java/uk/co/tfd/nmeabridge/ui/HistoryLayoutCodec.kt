package uk.co.tfd.nmeabridge.ui

/**
 * Encode / decode the user's History-screen layout to a flat string
 * suitable for SharedPreferences.
 *
 * Format: one chart per `\n`, each chart's left + right axis groups
 * separated by `|`, each axis group is comma-separated tokens of the
 * form `fieldId:argbInt`.
 *
 * Examples:
 *   ""                                — no charts
 *   "|"                               — one empty chart
 *   "nav.sog:-12345|"                 — one chart, one series on left
 *   "nav.sog:-1,nav.stw:-2|nav.depth:-3"   — left has two, right has one
 *   "engine.rpm:-1|\nbms.packV:-2|"   — two charts (newline between)
 *
 * Series whose field ids no longer appear in [HistoryFieldCatalog] are
 * silently dropped on decode (graceful upgrade behaviour).
 *
 * Avoids `org.json` because the Android stub on the unit-test JVM
 * returns nulls for every method, making round-trip tests impossible
 * without Robolectric.
 */
object HistoryLayoutCodec {

    private const val CHART_SEP = "\n"
    private const val SIDE_SEP = "|"
    private const val SERIES_SEP = ","
    private const val FIELD_COLOR_SEP = ":"

    fun encode(layout: List<HistoryChartConfig>): String =
        layout.joinToString(CHART_SEP) { c ->
            encodeRefs(c.left) + SIDE_SEP + encodeRefs(c.right)
        }

    fun decode(input: String): List<HistoryChartConfig> {
        if (input.isEmpty()) return emptyList()
        return input.split(CHART_SEP).map { line ->
            val parts = line.split(SIDE_SEP, limit = 2)
            HistoryChartConfig(
                left = decodeRefs(parts.getOrNull(0).orEmpty()),
                right = decodeRefs(parts.getOrNull(1).orEmpty()),
            )
        }
    }

    private fun encodeRefs(refs: List<HistorySeriesRef>): String =
        refs.joinToString(SERIES_SEP) { "${it.fieldId}${FIELD_COLOR_SEP}${it.colorArgb}" }

    private fun decodeRefs(s: String): List<HistorySeriesRef> {
        if (s.isEmpty()) return emptyList()
        return s.split(SERIES_SEP).mapNotNull { token ->
            val pp = token.split(FIELD_COLOR_SEP, limit = 2)
            if (pp.size != 2) return@mapNotNull null
            val id = pp[0]
            if (HistoryFieldCatalog.byId(id) == null) return@mapNotNull null
            val color = pp[1].toIntOrNull() ?: return@mapNotNull null
            HistorySeriesRef(id, color)
        }
    }
}
