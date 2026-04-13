package com.example.my_plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object CycloneDxMavenInvoker {
    private val LOG = LogInitializer.getLogger(CycloneDxMavenInvoker::class.java)

    class MavenExecutionException(
        message: String,
        val reason: String,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    fun generateSbom(mavenProjectDir: File, outputDir: String): File =
        generateSbom(mavenProjectDir, outputDir, null)

    fun generateSbom(mavenProjectDir: File, outputDir: String, indicator: ProgressIndicator?): File {
        require(File(mavenProjectDir, "pom.xml").exists()) { "pom.xml not found in ${mavenProjectDir.absolutePath}" }

        val mvnCmd = getMvnCmd(mavenProjectDir)

        val cmd = GeneralCommandLine(
            mvnCmd,
            "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom",
            "-DoutputFormat=xml",
            "-DoutputDirectory=$outputDir",
            "-DoutputName=bom",
            "-DincludeBomSerialNumber=false",
        )
            .withWorkDirectory(mavenProjectDir)
            .withCharset(StandardCharsets.UTF_8)
            .withRedirectErrorStream(true)

        val outputLines = mutableListOf<String>()
        val outputBuilder = StringBuilder()
        val handler = try {
            OSProcessHandler(cmd)
        } catch (t: Throwable) {
            throw MavenExecutionException(
                "Maven was not found or could not be started. Install Maven, add it to PATH, configure MAVEN_HOME/M2_HOME, or add a Maven wrapper (mvnw) to the project.",
                "maven_not_found",
                t
            )
        }
        val process = handler.process
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val line = event.text?.trimEnd() ?: ""
                if (line.isNotEmpty()) {
                    outputLines.add(line)
                }
                outputBuilder.append(line).append("\n")
            }
        })
        handler.startNotify()

        val timeoutMs = 5 * 60 * 1000L
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (process.isAlive) {
                indicator?.checkCanceled()
                if (System.currentTimeMillis() > deadline) {
                    handler.destroyProcess()
                    throw MavenExecutionException(
                        "SBOM generation timed out after ${timeoutMs / 1000} seconds.\n${outputBuilder.toString().trim()}",
                        "sbom_timeout"
                    )
                }
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
        } catch (e: ProcessCanceledException) {
            handler.destroyProcess()
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
            throw e
        }

        val exitCode = try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }
        if (exitCode != 0) {
            LOG.error("Maven SBOM generation failed with exit code " + exitCode)
            throw MavenExecutionException(
                "Maven failed while generating the SBOM (exit code $exitCode).\n${outputBuilder.toString().trim()}",
                "maven_command_failed"
            )
        }

        var bomFilePath: String? = null
        for (line in outputLines) {
            if (line.contains("CycloneDX: Writing and validating BOM (XML):")) {
                val detected = line.substringAfter("CycloneDX: Writing and validating BOM (XML):").trim()
                bomFilePath = detected
                if (bomFilePath.split("/").last() != "bom.xml") {
                    LOG.info("Unexpected BOM file name: " + bomFilePath)
                    val newSbomFilePath = File(outputDir, "bom.xml").absolutePath
                    if (renameFile(bomFilePath, newSbomFilePath)) {
                        LOG.info("Renamed to " + newSbomFilePath)
                    } else {
                        LOG.error("Failed to rename BOM file to bom.xml")
                    }
                }
            }
        }

        val bomFile = File(outputDir, "bom.xml")
        if (!waitForFile(bomFile, 2500)) {
            val detectedFile = bomFilePath?.let { path ->
                val candidate = File(path)
                if (candidate.isAbsolute) candidate else File(mavenProjectDir, path)
            }
            if (detectedFile != null && waitForFile(detectedFile, 1000) && detectedFile.exists()) {
                try {
                    Files.createDirectories(bomFile.toPath().parent)
                    if (detectedFile.absolutePath != bomFile.absolutePath) {
                        Files.copy(detectedFile.toPath(), bomFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        LOG.info("Copied SBOM from detected path to expected path: ${detectedFile.absolutePath} -> ${bomFile.absolutePath}")
                    }
                } catch (t: Throwable) {
                    LOG.warn("Detected SBOM exists but copy to bom.xml failed: ${t.message}")
                }
            }
        }
        if (!bomFile.exists()) {
            val fallback = findFallbackBom(outputDir, mavenProjectDir)
            if (fallback != null && fallback.exists()) {
                try {
                    Files.createDirectories(bomFile.toPath().parent)
                    if (fallback.absolutePath != bomFile.absolutePath) {
                        Files.copy(fallback.toPath(), bomFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    LOG.info("Recovered SBOM from fallback path: ${fallback.absolutePath}")
                } catch (t: Throwable) {
                    LOG.warn("Failed to recover SBOM from fallback ${fallback.absolutePath}: ${t.message}")
                }
            }
        }
        if (!bomFile.exists()) {
            LOG.warn("bom.xml not found after the generation: $bomFilePath")
            throw MavenExecutionException("SBOM not found after generation.", "sbom_missing")
        }

        LOG.info("✅ SBOM generated: ${bomFile.absolutePath}")
        return bomFile
    }

    private fun waitForFile(file: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.isFile && file.length() > 0L) {
                return true
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return file.exists()
            }
        }
        return file.exists() && file.isFile
    }

    private fun findFallbackBom(outputDir: String, mavenProjectDir: File): File? {
        val outDir = File(outputDir)
        val inOutputDir = outDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith("-cyclonedx.xml") }
            ?.maxByOrNull { it.lastModified() }
        if (inOutputDir != null) return inOutputDir

        val fromTarget = File(mavenProjectDir, "target")
        val inTarget = fromTarget.listFiles()
            ?.filter { it.isFile && (it.name == "bom.xml" || it.name.endsWith("-cyclonedx.xml")) }
            ?.maxByOrNull { it.lastModified() }
        return inTarget
    }

    fun getMvnCmd(mavenProjectDir: File) : String
    {
        return getMvnLocal(mavenProjectDir) //First, try getting a local maven installation from the target repo
            ?: getMvnFromHome() //If it's not there, look for MAVEN_HOME or M2_HOME environment variables
            ?: getMvnByOs() //If all else fails, check the OS to see what the maven command should be
    }

    fun getMvnLocal(mavenProjectDir: File): String?
    {
        if (File(mavenProjectDir, "mvnw").exists())
        {
            return "./mvnw"
        }
        return null
    }

    fun getMvnFromHome() : String?
    {
        val mavenHome = System.getenv("MAVEN_HOME") ?: System.getenv("M2_HOME")
        if (mavenHome != null)
        {
            val mvnCmd = File(mavenHome, "bin/${if (isWindows()) "mvn.cmd" else "mvn"}")
            if (mvnCmd.exists() && mvnCmd.canExecute()) {
                return mvnCmd.absolutePath
            }
        }
        return null
    }

    fun getMvnByOs() : String
    {
        return if (isWindows()) "mvn.cmd" else "mvn"
    }

    fun isWindows(): Boolean
    {
        val os = System.getProperty("os.name").lowercase()
        return os.contains("win")
    }

    fun renameFile(oldPath: String, newPath: String): Boolean {
        return try {
            // if oldPath does not exist, return false
            val oldFile = java.io.File(oldPath)
            if (!oldFile.exists()) {
                LOG.error("renameFile error: source file does not exist: $oldPath")
                return false
            }
            // if oldPath and newPath are the same, return true
            if (oldPath.trim() == newPath.trim()) {
                return true
            }
            val source = java.nio.file.Paths.get(oldPath.trim())
            val target = java.nio.file.Paths.get(newPath.trim())
            java.nio.file.Files.createDirectories(target.parent)
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (t: Throwable) {
            LOG.error("Generic renaming file error: ${t.message}")
            false
        }
    }
}
