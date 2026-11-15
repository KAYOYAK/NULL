package com.kayoyak.demoapp.data.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One measurement of one model.
 *
 * Kept rather than shown and forgotten: the reason to run a benchmark is to compare
 * two models, and a number that disappears when the dialog closes cannot be
 * compared to anything. [measuredAt] is stored because a speed measured on an older
 * build, or before the phone warmed up, is not the same claim as a fresh one.
 */
@Serializable
data class BenchmarkRecord(
    val modelId: String,
    val displayName: String,
    val measuredAt: Long,
    val timeToFirstTokenMillis: Long? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
)

@Serializable
private data class Stored(val records: List<BenchmarkRecord> = emptyList())

/**
 * The benchmark results, on disk.
 *
 * One record per model: a later run replaces the earlier one, because the point of
 * the list is "what does this model do on this phone now", not a history of every
 * run. The file is plain JSON -- numbers about a model, nothing about a person.
 */
class BenchmarkLog(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _records = MutableStateFlow(read())
    val records: StateFlow<List<BenchmarkRecord>> = _records.asStateFlow()

    /** Recording is the only write. A model measured twice keeps its latest number. */
    suspend fun record(entry: BenchmarkRecord) {
        val merged = (_records.value.filterNot { it.modelId == entry.modelId } + entry)
            .sortedBy { it.measuredAt }
        _records.value = merged
        write(merged)
    }

    private fun read(): List<BenchmarkRecord> {
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<Stored>(file.readText()).records.sortedBy { it.measuredAt }
        }.getOrDefault(emptyList())
    }

    private suspend fun write(records: List<BenchmarkRecord>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(Stored(records)))
            if (file.exists()) {
                file.delete()
            }
            temporary.renameTo(file)
        }
    }
}
