package com.atxx.jhmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The plugin bootstrap `scripts/Launcher.java` runs the fat jar with `java -jar`, which ignores the
 * JVM args baked into the Gradle `application` block (`applicationDefaultJvmArgs`, used by the bin/
 * start scripts). So the Launcher hard-codes the same args — and this guard fails the build if the
 * two lists drift apart (a shared resource file is impractical: the Launcher sets these args before
 * the jar it would read them from has been downloaded).
 */
class LauncherJvmArgsTest {

    @Test
    fun `Launcher JVM args match build gradle applicationDefaultJvmArgs`() {
        val gradleFile = File("build.gradle.kts")
        val launcherFile = File("scripts/Launcher.java")
        assertTrue(gradleFile.isFile, "build.gradle.kts not found at ${gradleFile.absolutePath}")
        assertTrue(launcherFile.isFile, "scripts/Launcher.java not found at ${launcherFile.absolutePath}")

        val block = Regex("""applicationDefaultJvmArgs\s*=\s*listOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(gradleFile.readText())
            ?.groupValues?.get(1)
            ?: error("applicationDefaultJvmArgs = listOf(...) not found in build.gradle.kts")
        // Only JVM args (-X… / -D…); never e.g. the Launcher's own "-jar".
        val gradleArgs = Regex(""""(-[XD][^"]*)"""").findAll(block).map { it.groupValues[1] }.toList()

        val launcherArgs = Regex("""cmd\.add\("(-[XD][^"]*)"\)""").findAll(launcherFile.readText())
            .map { it.groupValues[1] }.toList()

        assertTrue(gradleArgs.isNotEmpty(), "parsed no JVM args from build.gradle.kts")
        assertEquals(
            gradleArgs,
            launcherArgs,
            "Launcher.java JVM args drifted from build.gradle.kts applicationDefaultJvmArgs",
        )
    }
}
