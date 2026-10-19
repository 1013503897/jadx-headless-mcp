package com.atxx.jhmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Compiles src/test/resources/fixtures/nested-src (demo.Outer + Nested/Inner + demo.Unrelated) into a
 * throwaway jar that real jadx can load. Shared by the tests that need an actual loaded session
 * without committing a binary APK. See MetadataClassIndexTest for the original inline version.
 */
internal object NestedFixture {

    fun buildJar(): File {
        val srcRoot = File(
            requireNotNull(javaClass.classLoader.getResource("fixtures/nested-src")) {
                "missing fixtures/nested-src"
            }.toURI(),
        )
        val javaFiles = srcRoot.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        assertTrue(javaFiles.isNotEmpty(), "no .java files under fixtures/nested-src")

        val work = Files.createTempDirectory("jhmcp-tool-fixture").toFile()
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
     * Locate `javac` without a POSIX shell: `<java.home>/bin/javac[.exe]`, then the JDK-next-to-JRE
     * layout, then a bare program name the OS resolves from PATH. (Mirrors the Windows-safe lookup in
     * MetadataClassIndexTest — a `which javac` fallback returns an unexecutable MSYS path on Windows.)
     */
    private fun findJavac(): String {
        val exe = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "javac.exe" else "javac"
        val home = File(System.getProperty("java.home"))
        listOf(File(home, "bin/$exe"), File(home.parentFile, "bin/$exe"))
            .firstOrNull { it.isFile }
            ?.let { return it.absolutePath }
        return exe
    }
}
