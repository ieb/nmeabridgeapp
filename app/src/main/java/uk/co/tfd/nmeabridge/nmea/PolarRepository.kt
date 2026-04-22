package uk.co.tfd.nmeabridge.nmea

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages the user's library of polar files and the currently active polar.
 *
 * Files live under filesDir/polars/&lt;name&gt;.csv. The built-in Pogo 1250 polar is
 * copied from `assets/polars/pogo1250.csv` on first use and protected from
 * deletion. The active polar name is persisted in SharedPreferences.
 */
class PolarRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dir: File = File(context.filesDir, "polars").apply { mkdirs() }

    private val _active = MutableStateFlow(load(activeName()))
    val active: StateFlow<PolarTable> = _active.asStateFlow()

    private val _names = MutableStateFlow(list())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    fun list(): List<String> {
        ensureBuiltInPresent()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    fun setActive(name: String) {
        val table = load(name)
        prefs.edit().putString(KEY_ACTIVE, table.name).apply()
        _active.value = table
    }

    /** Import a CSV from a SAF URI. Validates before saving. */
    fun import(uri: Uri, proposedName: String): Result<String> = runCatching {
        val text = context.contentResolver.openInputStream(uri)!!
            .bufferedReader().use { it.readText() }
        val name = sanitizeName(proposedName)
        // Validate first — throws if malformed.
        PolarTable.parseCsv(name, text).getOrThrow()
        File(dir, "$name.csv").writeText(text)
        _names.value = list()
        name
    }

    /** Persist `table` under its current name, making it active. Safe for
     *  user-named polars. Caller must pick a non-builtin name before
     *  calling; `forbidBuiltinName` flags this for the UI. */
    fun save(table: PolarTable): Result<Unit> = runCatching {
        require(table.name != BUILTIN_NAME) { "cannot overwrite built-in polar" }
        val name = sanitizeName(table.name)
        File(dir, "$name.csv").writeText(table.toCsv())
        _names.value = list()
        setActive(name)
    }

    /** Save `table` under a new name (used by Save-As). */
    fun saveAs(newName: String, table: PolarTable): Result<String> = runCatching {
        val name = sanitizeName(newName)
        require(name != BUILTIN_NAME) { "cannot use the built-in name" }
        val renamed = PolarTable(name, table.twsAxis.copyOf(), table.twaAxis.copyOf(),
            Array(table.speeds.size) { table.speeds[it].copyOf() })
        File(dir, "$name.csv").writeText(renamed.toCsv())
        _names.value = list()
        setActive(name)
        name
    }

    fun export(uri: Uri, table: PolarTable): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
            os.write(table.toCsv().toByteArray())
        }
    }

    fun delete(name: String) {
        if (name == BUILTIN_NAME) return
        File(dir, "$name.csv").delete()
        _names.value = list()
        if (activeName() == name) setActive(BUILTIN_NAME)
    }

    private fun activeName(): String = prefs.getString(KEY_ACTIVE, BUILTIN_NAME) ?: BUILTIN_NAME

    private fun load(name: String): PolarTable {
        ensureBuiltInPresent()
        val file = File(dir, "$name.csv")
        val (realName, text) = if (file.exists()) {
            name to file.readText()
        } else {
            BUILTIN_NAME to File(dir, "$BUILTIN_NAME.csv").readText()
        }
        return PolarTable.parseCsv(realName, text).getOrThrow()
    }

    private fun ensureBuiltInPresent() {
        val target = File(dir, "$BUILTIN_NAME.csv")
        if (target.exists()) return
        context.assets.open("polars/$BUILTIN_NAME.csv").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun sanitizeName(raw: String): String {
        val base = raw.substringBeforeLast('.').trim()
            .replace(Regex("[^A-Za-z0-9_. -]"), "_")
            .take(64)
        return base.ifBlank { "polar" }
    }

    companion object {
        const val BUILTIN_NAME = "pogo1250"
        private const val PREFS_NAME = "nmea_bridge_settings"
        private const val KEY_ACTIVE = "active_polar"
    }
}
