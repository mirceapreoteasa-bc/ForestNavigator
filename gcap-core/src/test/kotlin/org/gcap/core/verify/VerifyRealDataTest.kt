package org.gcap.core.verify

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the verifier against the real FOR_NAV project folder. Skipped when the data is
 * absent so the suite still runs anywhere.
 */
class VerifyRealDataTest {

    private val dir = File(System.getenv("FORNAV_DATA") ?: (System.getProperty("user.home") + "/ForestNavigator/FOR_NAV"))

    @Test
    fun `verifies the real project folder`() {
        assumeTrue(dir.exists(), "missing ${dir.path}")
        val bytes = HashMap<String, ByteArray>()
        dir.listFiles()?.forEach { f ->
            if (f.isFile) f.inputStream().use { bytes[f.name] = it.readBytes() }
        }
        val source = LayerSource.of(bytes)

        val classifier = CrsClassifier { prj ->
            val t = prj.orEmpty().uppercase().filter { it.isLetterOrDigit() }
            when {
                t.isEmpty() -> CrsVerdict("assumed UTM", 10)
                t.startsWith("GEOGCS") || (t.contains("GCSWGS1984") && !t.contains("UTM")) ->
                    CrsVerdict("WGS84", 0, recognised = true, geographic = true)
                t.contains("UTMZONE") -> {
                    val z = Regex("UTMZONE(\\d{1,2})N?").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 10
                    CrsVerdict("UTM", z, recognised = true)
                }
                else -> CrsVerdict("?", 0, recognised = false)
            }
        }

        val report = LayerVerifier(source, classifier).verify()
        println("VERIFYREAL " + report.summary)
        println("VERIFYREAL " + report.crsNote)
        println("VERIFYREAL " + report.extentNote)
        report.lines.forEach { println("VERIFYREAL   $it") }

        assertTrue(report.layers.isNotEmpty(), "no layers found")
        assertTrue(report.summary.contains("layers:"), report.summary)
    }
}
