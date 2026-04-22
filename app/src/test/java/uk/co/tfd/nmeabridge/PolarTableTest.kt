package uk.co.tfd.nmeabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.tfd.nmeabridge.nmea.PolarTable

class PolarTableTest {

    private fun loadPogo(): PolarTable {
        val csv = javaClass.classLoader!!
            .getResourceAsStream("polars/pogo1250.csv")!!
            .bufferedReader()
            .use { it.readText() }
        return PolarTable.parseCsv("pogo1250", csv).getOrThrow()
    }

    @Test
    fun parse_pogoCsv_succeeds() {
        val p = loadPogo()
        assertEquals(17, p.twsAxis.size)
        assertEquals(24, p.twaAxis.size)
        assertEquals(0.0, p.twsAxis.first(), 0.0)
        assertEquals(60.0, p.twsAxis.last(), 0.0)
        assertEquals(180.0, p.twaAxis.last(), 0.0)
    }

    @Test
    fun polar_gridPointLookup() {
        val p = loadPogo()
        // At (tws=10 kn, twa=32°): matches firmware grid value 6.2 kn.
        assertEquals(6.2, p.polarSpeed(10.0, 32.0), 1e-6)
    }

    @Test
    fun polar_belowAxisReturnsZero() {
        assertEquals(0.0, loadPogo().polarSpeed(-5.0, -10.0), 1e-6)
    }

    @Test
    fun polar_aboveAxisClampsToCorner() {
        // Last cell of the grid: tws=60, twa=180 → 3.9 kn.
        assertEquals(3.9, loadPogo().polarSpeed(1000.0, 1000.0), 1e-6)
    }

    @Test
    fun polar_offGridValueLiesBetweenNeighbours() {
        val p = loadPogo()
        val v = p.polarSpeed(11.0, 47.0)
        val n = listOf(
            p.polarSpeed(10.0, 45.0),
            p.polarSpeed(12.0, 45.0),
            p.polarSpeed(10.0, 52.0),
            p.polarSpeed(12.0, 52.0)
        )
        assertTrue("interp between $n got $v", v >= n.min() - 1e-6)
        assertTrue("interp between $n got $v", v <= n.max() + 1e-6)
    }

    @Test
    fun parse_raggedRow_fails() {
        val csv = """
            twa/tws;6;10;14
            32;5;6;7
            60;5;6
        """.trimIndent()
        assertTrue(PolarTable.parseCsv("x", csv).isFailure)
    }

    @Test
    fun parse_nonMonotonicTws_fails() {
        val csv = """
            twa/tws;6;10;8
            32;5;6;7
        """.trimIndent()
        assertTrue(PolarTable.parseCsv("x", csv).isFailure)
    }

    @Test
    fun parse_negativeSpeed_fails() {
        val csv = """
            twa/tws;6;10;14
            32;5;-1;7
        """.trimIndent()
        assertTrue(PolarTable.parseCsv("x", csv).isFailure)
    }

    @Test
    fun parse_commaDelimiter_works() {
        val csv = """
            twa/tws,6,10,14
            32,5,6,7
            90,4,5,6
        """.trimIndent()
        val p = PolarTable.parseCsv("x", csv).getOrThrow()
        assertEquals(6.0, p.polarSpeed(10.0, 32.0), 1e-9)
    }
}
