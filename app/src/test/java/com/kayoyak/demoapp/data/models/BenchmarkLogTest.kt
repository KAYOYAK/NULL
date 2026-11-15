package com.kayoyak.demoapp.data.models

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The benchmark log exists so that running a benchmark twice means something.
 *
 * These tests pin the two rules that make it useful: one number per model (a fresh
 * run replaces the stale one) and survival across a process, which is the whole
 * difference between a log and a dialog.
 */
class BenchmarkLogTest {

    @get:Rule val temporary = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun record(
        modelId: String,
        measuredAt: Long,
        decode: Double,
    ) = BenchmarkRecord(
        modelId = modelId,
        displayName = modelId.uppercase(),
        measuredAt = measuredAt,
        timeToFirstTokenMillis = 120L,
        prefillTokensPerSecond = 300.0,
        decodeTokensPerSecond = decode,
    )

    @Test
    fun `a second run replaces the first number for the same model`() = runBlocking {
        val file = File(temporary.newFolder(), "benchmarks.json")
        val log = BenchmarkLog(file)

        log.record(record("qwen", now, 4.0))
        log.record(record("qwen", now + 1_000, 6.5))

        assertEquals("One row per model", 1, log.records.value.size)
        assertEquals("The newer number wins", 6.5, log.records.value.single().decodeTokensPerSecond)
    }

    @Test
    fun `results survive a new instance, which is what makes them comparable`() = runBlocking {
        val file = File(temporary.newFolder(), "benchmarks.json")
        BenchmarkLog(file).record(record("qwen", now, 4.0))

        val reopened = BenchmarkLog(file)

        assertEquals(1, reopened.records.value.size)
        assertEquals("qwen", reopened.records.value.single().modelId)
        assertEquals(300.0, reopened.records.value.single().prefillTokensPerSecond)
    }

    @Test
    fun `a damaged file is treated as no measurements rather than a crash`() {
        val file = temporary.newFile("benchmarks.json")
        Files.write(file.toPath(), "{ not json".toByteArray())

        val log = BenchmarkLog(file)

        assertTrue("An unreadable log reads as empty", log.records.value.isEmpty())
    }

    @Test
    fun `the file is plain json with no session data in it`() = runBlocking {
        val file = File(temporary.newFolder(), "benchmarks.json")
        BenchmarkLog(file).record(record("qwen", now, 4.0))

        val text = file.readText()

        assertTrue("Readable on purpose: these are numbers about a model", text.contains("qwen"))
        assertTrue("Nothing about a conversation", !text.contains("message"))
    }
}
