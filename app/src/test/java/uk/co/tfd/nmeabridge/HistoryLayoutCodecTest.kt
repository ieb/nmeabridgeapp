package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.tfd.nmeabridge.ui.HistoryChartConfig
import uk.co.tfd.nmeabridge.ui.HistoryLayoutCodec
import uk.co.tfd.nmeabridge.ui.HistorySeriesRef

class HistoryLayoutCodecTest {

    @Test
    fun emptyLayout_roundTrips() {
        val src = emptyList<HistoryChartConfig>()
        val out = HistoryLayoutCodec.decode(HistoryLayoutCodec.encode(src))
        assertEquals(src, out)
    }

    @Test
    fun emptyChart_roundTrips() {
        val src = listOf(HistoryChartConfig())
        val out = HistoryLayoutCodec.decode(HistoryLayoutCodec.encode(src))
        assertEquals(src, out)
    }

    @Test
    fun layoutWithSeries_roundTrips() {
        val src = listOf(
            HistoryChartConfig(
                left = listOf(
                    HistorySeriesRef("nav.sog", -100),
                    HistorySeriesRef("nav.stw", -200),
                ),
                right = listOf(HistorySeriesRef("nav.depth", -300)),
            ),
            HistoryChartConfig(
                left = listOf(HistorySeriesRef("engine.rpm", -400)),
            ),
        )
        val out = HistoryLayoutCodec.decode(HistoryLayoutCodec.encode(src))
        assertEquals(src, out)
    }

    @Test
    fun unknownFieldId_isDropped() {
        // The decoder must drop entries whose field id isn't in the
        // catalog (e.g. one removed in a future release).
        val raw = "nav.sog:-1,future.unknown:-2|"
        val out = HistoryLayoutCodec.decode(raw)
        assertEquals(1, out.size)
        assertEquals(1, out[0].left.size)
        assertEquals("nav.sog", out[0].left[0].fieldId)
    }

    @Test
    fun garbageToken_isDropped() {
        // Token without the colon separator is skipped silently.
        val raw = "this-is-not-a-token|"
        val out = HistoryLayoutCodec.decode(raw)
        assertEquals(1, out.size)
        assertEquals(emptyList<HistorySeriesRef>(), out[0].left)
        assertEquals(emptyList<HistorySeriesRef>(), out[0].right)
    }

    @Test
    fun colorParseFailure_isDropped() {
        val raw = "nav.sog:not-a-number|"
        val out = HistoryLayoutCodec.decode(raw)
        assertEquals(1, out.size)
        assertEquals(emptyList<HistorySeriesRef>(), out[0].left)
    }
}
