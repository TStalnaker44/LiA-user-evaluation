package com.example.my_plugin

import com.example.my_plugin.license.LicenseQuestionnaireDialog
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PrerequisiteChecker {
    private val LOG = LogInitializer.getLogger(PrerequisiteChecker::class.java)
    private val lastRunByProject = ConcurrentHashMap<String, Long>()
    private const val MIN_INTERVAL_MS = 60_000L

    fun run(project: Project, trigger: String) {
        val key = project.locationHash ?: project.basePath ?: project.name
        val now = System.currentTimeMillis()
        val lastRun = lastRunByProject[key] ?: 0L
        if (now - lastRun < MIN_INTERVAL_MS) return
        lastRunByProject[key] = now

        ApplicationManager.getApplication().executeOnPooledThread {
            val startedAtMs = System.currentTimeMillis()
            val findings = mutableListOf<PrerequisiteFinding>()
            try {
                findings += checkProjectBasePath(project)
                findings += checkLicenseToolDirectory(project)
                findings += checkLicensingConfiguration(project)
                findings += checkMaven(project)
                findings += checkModelConfiguration(project)

                val issues = findings.filter { it.severity != Severity.OK }
                UsageLogger.logInteraction(
                    project,
                    null,
                    "prerequisite_check_completed",
                    mapOf(
                        "trigger" to trigger,
                        "durationMs" to (System.currentTimeMillis() - startedAtMs),
                        "issueCount" to issues.size,
                        "issues" to issues.map { mapOf("id" to it.id, "severity" to it.severity.name, "message" to it.message) }
                    )
                )
                if (issues.isNotEmpty()) {
                    notifyIssues(project, issues)
                }
            } catch (t: Throwable) {
                UsageLogger.logInteraction(
                    project,
                    null,
                    "prerequisite_check_failed",
                    mapOf(
                        "trigger" to trigger,
                        "error" to (t.message ?: t.javaClass.simpleName),
                        "durationMs" to (System.currentTimeMillis() - startedAtMs)
                    )
                )
                LOG.warn("LiA prerequisite check failed: ${t.message}", t)
            }
        }
    }

    private fun checkProjectBasePath(project: Project): List<PrerequisiteFinding> {
        val basePath = project.basePath
        return when {
            basePath.isNullOrBlank() -> listOf(error("project_base_path_missing", "Project base path is not available."))
            !File(basePath).isDirectory -> listOf(error("project_base_path_invalid", "Project base path does not point to a valid directory."))
            else -> emptyList()
        }
    }

    private fun checkLicenseToolDirectory(project: Project): List<PrerequisiteFinding> {
        val basePath = project.basePath ?: return emptyList()
        val dir = File(basePath, ".license-tool")
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                listOf(error("license_tool_dir_create_failed", "LiA could not create the .license-tool directory."))
            } else if (!dir.canWrite()) {
                listOf(error("license_tool_dir_not_writable", "The .license-tool directory is not writable."))
            } else {
                emptyList()
            }
        } catch (t: Throwable) {
            listOf(error("license_tool_dir_check_failed", "LiA could not access .license-tool: ${t.message ?: t.javaClass.simpleName}"))
        }
    }

    private fun checkLicensingConfiguration(project: Project): List<PrerequisiteFinding> {
        val basePath = project.basePath ?: return emptyList()
        val configFile = File(basePath, ".license-tool/license-survey.json")
        return if (configFile.exists() && configFile.isFile && configFile.length() > 0L) {
            emptyList()
        } else {
            listOf(warn("licensing_configuration_missing", "Licensing Configuration is missing. Complete it to enable contextual licensing guidance and automatic compliance checks."))
        }
    }

    private fun checkMaven(project: Project): List<PrerequisiteFinding> {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = File(basePath)
        val pom = baseDir.walkTopDown().firstOrNull { it.name == "pom.xml" }
            ?: return listOf(warn("pom_missing", "No pom.xml was found. LiA currently analyzes Maven projects."))

        return try {
            val mvnCmd = CycloneDxMavenInvoker.getMvnCmd(pom.parentFile)
            val command = GeneralCommandLine(mvnCmd, "-v")
                .withWorkDirectory(pom.parentFile)
                .withCharset(StandardCharsets.UTF_8)
                .withRedirectErrorStream(true)
            val handler = OSProcessHandler(command)
            val process = handler.process
            handler.startNotify()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                handler.destroyProcess()
                return listOf(error("maven_timeout", "Maven was found but did not respond to 'mvn -v' within 10 seconds."))
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                emptyList()
            } else {
                listOf(error("maven_version_failed", "Maven was found but 'mvn -v' failed with exit code $exitCode."))
            }
        } catch (t: Throwable) {
            listOf(error("maven_not_found", "Maven was not found or could not be started. Install Maven, add it to PATH, configure MAVEN_HOME/M2_HOME, or add a Maven wrapper (mvnw) to the project."))
        }
    }

    private fun checkModelConfiguration(project: Project): List<PrerequisiteFinding> {
        val model = ModelSettings.getSelectedModel(project)
        return when (ModelSettings.getProviderForModel(model)) {
            ModelProvider.OPENAI -> checkOpenAi(project)
            ModelProvider.OLLAMA -> checkOllama(project)
        }
    }

    private fun checkOpenAi(project: Project): List<PrerequisiteFinding> {
        val apiKey = ApiKeyStore.getOpenAiKey(project)
        if (apiKey.isNullOrBlank()) {
            return listOf(warn("openai_key_missing", "OpenAI API key is missing for the selected model. Open Settings to configure it."))
        }
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI("https://api.openai.com/v1/models"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()
            val response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.discarding())
            when (response.statusCode()) {
                in 200..299 -> emptyList()
                401 -> listOf(error("openai_key_invalid", "OpenAI rejected the configured API key. Open Settings and verify it."))
                else -> listOf(warn("openai_unreachable", "OpenAI connectivity check returned HTTP ${response.statusCode()}."))
            }
        } catch (t: Throwable) {
            listOf(warn("openai_unreachable", "OpenAI connectivity check failed: ${t.message ?: t.javaClass.simpleName}."))
        }
    }

    private fun checkOllama(project: Project): List<PrerequisiteFinding> {
        val host = ModelSettings.getOllamaHost(project)
        if (host.isBlank()) {
            return listOf(warn("ollama_host_missing", "Ollama host is missing for the selected model. Open Settings to configure it."))
        }
        val normalized = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI(normalized.trimEnd('/') + "/api/tags"))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) {
                emptyList()
            } else {
                listOf(error("ollama_unreachable", "Ollama host returned HTTP ${response.statusCode()}. Check the host in Settings and ensure Ollama is running."))
            }
        } catch (t: Throwable) {
            listOf(error("ollama_unreachable", "Ollama host is unreachable. Check the host in Settings and ensure Ollama is running."))
        }
    }

    private fun notifyIssues(project: Project, issues: List<PrerequisiteFinding>) {
        val content = issues.joinToString("<br>") { "- ${escapeNotificationHtml(it.message)}" }
        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Licensing Tool")
                .createNotification("LiA setup needs attention", content, NotificationType.WARNING)
            notification.addAction(NotificationAction.createSimple("Open Settings") {
                ToolWindowManager.getInstance(project).getToolWindow("LiA")?.show {
                    ApiKeysDialog(project).show()
                }
            })
            notification.addAction(NotificationAction.createSimple("Open Licensing Configuration") {
                ToolWindowManager.getInstance(project).getToolWindow("LiA")?.show {
                    LicenseQuestionnaireDialog(project).show()
                }
            })
            notification.notify(project)
        }
    }

    private fun escapeNotificationHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun error(id: String, message: String) = PrerequisiteFinding(id, Severity.ERROR, message)
    private fun warn(id: String, message: String) = PrerequisiteFinding(id, Severity.WARNING, message)

    private data class PrerequisiteFinding(val id: String, val severity: Severity, val message: String)

    private enum class Severity {
        OK,
        WARNING,
        ERROR
    }
}
