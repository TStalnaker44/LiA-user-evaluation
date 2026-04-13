package com.example.my_plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object MavenDependencyTreeInvoker {
    private val LOG = LogInitializer.getLogger(MavenDependencyTreeInvoker::class.java)

    fun generateDependencyTree(mavenProjectDir: File, outputFile: File, indicator: ProgressIndicator?): File {
        require(File(mavenProjectDir, "pom.xml").exists()) { "pom.xml not found in ${mavenProjectDir.absolutePath}" }

        outputFile.parentFile?.mkdirs()
        val mvnCmd = CycloneDxMavenInvoker.getMvnCmd(mavenProjectDir)
        val command = GeneralCommandLine(
            mvnCmd,
            "dependency:tree",
            "-DoutputType=text",
            "-DoutputFile=${outputFile.absolutePath}",
            "-DappendOutput=false"
        )
            .withWorkDirectory(mavenProjectDir)
            .withCharset(StandardCharsets.UTF_8)
            .withRedirectErrorStream(true)

        val output = StringBuilder()
        val handler = try {
            OSProcessHandler(command)
        } catch (t: Throwable) {
            throw CycloneDxMavenInvoker.MavenExecutionException(
                "Maven was not found or could not be started while generating the dependency tree.",
                "maven_not_found",
                t
            )
        }
        val process = handler.process
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                output.append(event.text ?: "")
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
                    throw CycloneDxMavenInvoker.MavenExecutionException(
                        "Dependency tree generation timed out after ${timeoutMs / 1000} seconds.\n${output.toString().trim()}",
                        "dependency_tree_timeout"
                    )
                }
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
        } catch (e: ProcessCanceledException) {
            handler.destroyProcess()
            throw e
        }

        val exitCode = try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }
        if (exitCode != 0) {
            LOG.error("Maven dependency:tree failed with exit code $exitCode")
            throw CycloneDxMavenInvoker.MavenExecutionException(
                "Maven dependency:tree failed (exit code $exitCode).\n${output.toString().trim()}",
                "dependency_tree_command_failed"
            )
        }
        if (!outputFile.exists()) {
            throw CycloneDxMavenInvoker.MavenExecutionException(
                "Dependency tree output file was not created: ${outputFile.absolutePath}",
                "dependency_tree_missing"
            )
        }
        return outputFile
    }
}
