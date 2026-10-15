package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiagnosticReportSectionTest {
    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private fun writeLog(name: String, line: String, times: Int) {
        val file = File(context.filesDir, "logs/$name")
        File(context.filesDir, "logs").mkdirs()
        file.writeText((1..times).joinToString("\n") { "$line $it" })
    }

    /** The whole point: one broken provider used to cost the driver the entire report. */
    @Test fun aFailingSectionIsNamedAndTheRestOfTheReportSurvives() {
        val report = buildString {
            reportSection("first") { appendLine("ok") }
            reportSection("broken") { throw IllegalStateException("probe is gone") }
            reportSection("last") { appendLine("still here") }
        }
        assertTrue(report, report.contains("--- first ---"))
        assertTrue(report, report.contains("section unavailable: IllegalStateException: probe is gone"))
        assertTrue(report, report.contains("still here"))
    }

    /** Errors count too: an OutOfMemoryError in one provider is what made the export fail silently. */
    @Test fun anErrorIsCaughtAsWellAsAnException() {
        val report = buildString { reportSection("heavy") { throw OutOfMemoryError("Java heap space") } }
        assertTrue(report, report.contains("section unavailable: OutOfMemoryError"))
    }

    @Test fun theNewestLogLinesAreKeptAndTheOlderOnesAreCounted() {
        // Derived from the budget, so raising it does not quietly stop this covering the trimming.
        // Kept well under the 700 characters redaction truncates to, so the number stays on the line.
        val padding = "x".repeat(100)
        val perFile = MAX_REPORT_LOG_CHARS / (padding.length + 11) * 3 / 4
        writeLog("previous.log", "old $padding", perFile)
        writeLog("diplay.log", "new $padding", perFile)
        val report = buildString { appendSessionLogs(context) }

        assertTrue(report.length.toString(), report.length <= MAX_REPORT_LOG_CHARS + 200)
        assertTrue(report, report.contains("(older log lines omitted: "))
        // The live log is last in REPORT_NAMES, so it is what survives.
        assertTrue(report, report.contains("new $padding $perFile"))
        assertFalse(report, report.contains("old $padding 1\n"))
    }

    @Test fun ashortLogIsKeptWholeWithItsHeader() {
        writeLog("diplay.log", "line", 3)
        val report = buildString { appendSessionLogs(context) }

        assertFalse(report, report.contains("older log lines omitted"))
        assertTrue(report, report.contains("--- diplay.log ---"))
        assertEquals(3, Regex("^line \\d+$", RegexOption.MULTILINE).findAll(report).count())
    }

    /** Redaction still runs on every line that reaches the report. */
    @Test fun aSecretLineNeverReachesTheReport() {
        writeLog("diplay.log", "password=hunter2 attempt", 2)
        val report = buildString { appendSessionLogs(context) }
        assertFalse(report, report.contains("hunter2"))
    }
}
