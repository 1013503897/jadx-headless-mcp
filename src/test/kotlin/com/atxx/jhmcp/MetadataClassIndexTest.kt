package com.atxx.jhmcp

import jadx.core.dex.nodes.ProcessState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Regression for https://github.com/1013503897/jadx-headless-mcp/issues/39:
 * exact class resolve + summarizeClass must use ClassNode / classesWithInners metadata
 * and must NOT walk JavaClass.innerClasses (which calls load() and decompiles the APK).
 *
 * Builds a tiny jar (Outer + Nested/Inner + Unrelated) from
 * src/test/resources/fixtures/nested-src at test startup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetadataClassIndexTest {

    private lateinit var fixtureJar: File

    @BeforeAll
    fun buildFixtureJar() {
        fixtureJar = compileNestedFixture()
    }

    @Test
    fun `resolve and summarize leave unrelated classes NOT_LOADED`() {
        val config = SessionConfig(
            maxSourceBytes = 64_000,
            codeScanCap = 100,
            decompileTimeoutMs = 30_000L,
            threads = 1,
            resourceMode = ResourceMode.NONE,
        )
        JadxSession.open(fixtureJar.absolutePath, config).use { session ->
            assertAllNotLoaded(session, "after load")

            val resolved = session.resolveClass("demo.Outer")
            assertNotNull(resolved)
            assertEquals("demo.Outer", resolved!!.fullName)
            assertAllNotLoaded(session, "after resolve Outer")

            // Nested forms must be addressable without walking JavaClass.innerClasses.
            val nested = session.resolveClass("demo.Outer\$Nested")
            assertNotNull(nested, "Outer\$Nested should resolve via classesWithInners index")
            assertEquals("demo.Outer.Nested", nested!!.fullName)
            assertAllNotLoaded(session, "after resolve Nested")

            val summary = session.summarizeClass(resolved)
            assertEquals(null, summary["error"], "summary error: ${summary["error"]}")
            assertEquals("class_node_metadata", summary["inventory"])
            @Suppress("UNCHECKED_CAST")
            val methods = summary["methods"] as List<Map<String, Any>>
            assertTrue(methods.any { it["name"] == "outerMethod" }, "methods=$methods")
            @Suppress("UNCHECKED_CAST")
            val inners = summary["inner_classes"] as List<*>
            assertTrue(
                inners.any { it.toString().contains("Nested") || it.toString().contains("Inner") },
                "inners=$inners",
            )
            assertAllNotLoaded(session, "after summarizeClass")

            val unrelated = session.resolveClass("demo.Unrelated")
            assertNotNull(unrelated)
            assertEquals(ProcessState.NOT_LOADED, unrelated!!.classNode.state)

            // Explicit source may decompile Outer within timeout — Unrelated stays cold.
            val src = session.getClassSource(resolved)
            assertTrue(src.contains("outerMethod") || src.contains("class Outer"), "src=$src")
            assertEquals(
                ProcessState.NOT_LOADED,
                unrelated.classNode.state,
                "source of Outer must not process Unrelated",
            )
        }
    }

    @Test
    fun `package filter still hides excluded packages from allClasses`() {
        val config = SessionConfig(
            includePackages = listOf("demo.Outer"),
            threads = 1,
            resourceMode = ResourceMode.NONE,
        )
        JadxSession.open(fixtureJar.absolutePath, config).use { session ->
            val names = session.allClasses.map { it.fullName }.toSet()
            assertTrue(names.any { it == "demo.Outer" || it.startsWith("demo.Outer.") }, "names=$names")
            assertFalse(names.contains("demo.Unrelated"), "Unrelated must be filtered out: $names")
        }
    }

    private fun assertAllNotLoaded(session: JadxSession, label: String) {
        val bad = session.allClasses
            .filter { it.classNode.state != ProcessState.NOT_LOADED }
            .map { "${it.fullName}=${it.classNode.state}" }
        assertTrue(bad.isEmpty(), "$label: expected all NOT_LOADED, but processed: $bad")
    }

    /**
     * Compile fixtures/nested-src Java sources with javac and pack into a temp jar.
     * Avoids committing a binary jar while still exercising real jadx load paths.
     */
    private fun compileNestedFixture(): File {
        val srcRoot = File(
            requireNotNull(javaClass.classLoader.getResource("fixtures/nested-src")) {
                "missing fixtures/nested-src"
            }.toURI(),
        )
        val javaFiles = srcRoot.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        assertTrue(javaFiles.isNotEmpty(), "no .java files under fixtures/nested-src")

        val work = Files.createTempDirectory("jhmcp-meta-fixture").toFile()
        work.deleteOnExit()
        val classesDir = File(work, "classes").also { it.mkdirs() }
        val javac = findJavac()
        val cmd = mutableListOf(javac, "-encoding", "UTF-8", "-d", classesDir.absolutePath)
        cmd.addAll(javaFiles.map { it.absolutePath })
        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw AssertionError(
                "javac could not be started ($javac) — the fixture needs a JDK " +
                    "(set JAVA_HOME to a JDK, or put javac on PATH): ${e.message}",
                e,
            )
        }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "javac timed out")
        assertEquals(0, proc.exitValue(), "javac failed: $out")

        val jar = File(work, "nested-classes.jar")
        JarOutputStream(jar.outputStream()).use { jos ->
            classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                val entryName = f.relativeTo(classesDir).invariantSeparatorsPath
                jos.putNextEntry(JarEntry(entryName))
                f.inputStream().use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }
        return jar
    }

    /**
     * Locate `javac` without relying on a POSIX shell: `<java.home>/bin/javac[.exe]`, then the
     * JDK-next-to-JRE layout, then a bare program name that the OS resolves from PATH.
     *
     * The previous `which javac` fallback broke on Windows — Git Bash answers with an MSYS path
     * (`/c/Program Files/...`) that CreateProcess cannot execute, so the test failed in
     * `@BeforeAll` on every Windows host.
     */
    private fun findJavac(): String {
        val exe = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            "javac.exe"
        } else {
            "javac"
        }
        val home = File(System.getProperty("java.home"))
        listOf(File(home, "bin/$exe"), File(home.parentFile, "bin/$exe"))
            .firstOrNull { it.isFile }
            ?.let { return it.absolutePath }
        return exe
    }
}
