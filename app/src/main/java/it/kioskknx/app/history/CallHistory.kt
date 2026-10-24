package it.kioskknx.app.history

import android.content.Context
import android.graphics.Bitmap
import it.kioskknx.app.config.ConfigRepository
import it.kioskknx.app.util.L
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class CallEntry(
    val id: Long,
    val time: Long,
    val remote: String,
    val answered: Boolean = false,
    val durationSec: Int = 0,
    val doorOpened: Boolean = false,
    val snapshot: String = "",
)

/** Storico delle ultime chiamate con foto del visitatore. */
object CallHistory {
    private const val MAX = 50
    private lateinit var file: File
    private lateinit var snapDir: File
    private val entries = mutableListOf<CallEntry>()
    private var currentId = 0L

    fun init(context: Context) {
        file = File(context.filesDir, "call_history.json")
        snapDir = File(context.filesDir, "snapshots").apply { mkdirs() }
        try {
            if (file.exists()) {
                entries += ConfigRepository.json.decodeFromString(ListSerializer(CallEntry.serializer()), file.readText())
            }
        } catch (e: Exception) {
            L.w("Storico", "Storico illeggibile, azzerato", e)
        }
    }

    @Synchronized
    fun list(): List<CallEntry> = entries.toList()

    @Synchronized
    fun start(remote: String) {
        currentId = System.currentTimeMillis()
        entries.add(0, CallEntry(currentId, currentId, remote))
        while (entries.size > MAX) {
            entries.removeAt(entries.size - 1).snapshot.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        }
        persist()
    }

    private inline fun updateCurrent(block: (CallEntry) -> CallEntry) {
        val i = entries.indexOfFirst { it.id == currentId }
        if (i >= 0) {
            entries[i] = block(entries[i])
            persist()
        }
    }

    @Synchronized fun markAnswered() = updateCurrent { it.copy(answered = true) }
    @Synchronized fun markDoorOpened() = updateCurrent { it.copy(doorOpened = true) }
    @Synchronized fun end(durationSec: Int) = updateCurrent { it.copy(durationSec = durationSec) }

    /** File di destinazione per lo snapshot della chiamata corrente (null se già presente). */
    @Synchronized
    fun newSnapshotFile(): File? {
        val e = entries.firstOrNull { it.id == currentId } ?: return null
        if (e.snapshot.isNotEmpty()) return null
        return File(snapDir, "call_${currentId}.jpg")
    }

    @Synchronized
    fun setSnapshot(f: File) {
        if (f.exists()) updateCurrent { it.copy(snapshot = f.absolutePath) }
    }

    fun saveBitmap(bmp: Bitmap) {
        val f = newSnapshotFile() ?: return
        try {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            setSnapshot(f)
        } catch (e: Exception) {
            L.w("Storico", "Snapshot non salvato", e)
        }
    }

    @Synchronized
    fun snapshotFor(id: String): File? {
        val e = if (id == "latest" || id.isBlank()) entries.firstOrNull { it.snapshot.isNotEmpty() }
        else entries.firstOrNull { it.id.toString() == id }
        return e?.snapshot?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
    }

    @Synchronized
    fun clear() {
        entries.forEach { e -> e.snapshot.takeIf { it.isNotEmpty() }?.let { File(it).delete() } }
        entries.clear()
        persist()
    }

    private fun persist() {
        try {
            file.writeText(ConfigRepository.json.encodeToString(ListSerializer(CallEntry.serializer()), entries))
        } catch (e: Exception) {
            L.w("Storico", "Salvataggio storico fallito", e)
        }
    }
}
