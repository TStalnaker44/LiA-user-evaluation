package com.example.my_plugin

import com.example.my_plugin.MyToolWindowBridge.Companion.getInstance
import com.example.my_plugin.license.LicenseQuestionnaireDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.example.my_plugin.MavenDependencyService
import com.example.my_plugin.LogInitializer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.diagnostic.Logger
import java.io.File

class ProjectStartupActivity : ProjectActivity {

    companion object {
        private val LOG: Logger = LogInitializer.getLogger(ProjectStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        // Initialize per-project logging (creates .license-tool/<project>.log)
        try {
            LogInitializer.setupLoggingForProject(project)
        } catch (t: Throwable) {
            // If logging initialization fails, continue but warn
            Logger.getInstance(ProjectStartupActivity::class.java).warn("Failed to initialize project logging", t)
        }

        LOG.info("Starting project activity for: ${project.name}")
        UsageLogger.logInteraction(project, null, "project_activity_started")

        val toolWindow = project.getService(MyToolWindowBridge::class.java).ui

        LOG.info("Project opened: ${project.name}")
        UsageLogger.logInteraction(project, null, "project_opened")
         // Check if the license survey file exists
         val licenseFile = java.io.File(project.basePath, ".license-tool/license-survey.json")
         if (licenseFile.exists()) {
            LOG.info("License survey file exists at: ${licenseFile.absolutePath}")
            UsageLogger.logInteraction(project, null, "survey_detected_on_startup", mapOf("path" to licenseFile.absolutePath))
             toolWindow?.enableSubmitButton()
             toolWindow?.enableInputArea()
         } else {
            LOG.info("License survey file does not exist.")
            UsageLogger.logInteraction(project, null, "survey_missing_on_startup")
             // Create the .license-tool folder if it does not exists
             ensureSurveyDir(project)
             // Wait for indexing to finish, then show toolwindow and dialog on the EDT
             DumbService.getInstance(project).runWhenSmart {
                 ApplicationManager.getApplication().invokeLater {
                     // All UI must be on EDT
                     LOG.debug("Project opened (invokeLater): ${project.name}")
                     LOG.debug("toolWindow: ${toolWindow}")
                     toolWindow?.let { tw ->
                         if (!tw.isToolWindowVisible(project)) {
                             tw.toggleToolWindowVisibility(project)
                         }
                     }
                     // showQuestionnaireEdt(project, toolWindow)
                     UsageLogger.logInteraction(project, null, "survey_dialog_prompted_on_startup")
                     showQuestionnaireEdt(project)
                 }
             }
         }

        // If the project contains one or more pom.xml files and no SBOM exists yet, generate SBOM on background thread.
        try {
            val base = project.basePath
            if (base != null) {
                val baseDir = File(base)
                val hasPom = baseDir.walkTopDown().any { it.name == "pom.xml" }
                if (hasPom) {
                    val sbomFile = File(base, ".license-tool/bom.xml")
                    if (!sbomFile.exists()) {
                        UsageLogger.logInteraction(project, null, "startup_sbom_queued")
                        // run SBOM generation in background with progress/cancel
                        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating SBOM", true) {
                            override fun run(indicator: ProgressIndicator) {
                                val startedAtMs = System.currentTimeMillis()
                                try {
                                    val svc = project.getService(MavenDependencyService::class.java)
                                    if (svc != null) {
                                        LOG.info("Triggering SBOM generation on project open for: ${project.name}")
                                        UsageLogger.logInteraction(project, null, "startup_sbom_started")
                                        indicator.isIndeterminate = true
                                        svc.genSbom(indicator)
                                        UsageLogger.logInteraction(
                                            project,
                                            null,
                                            "startup_sbom_completed",
                                            mapOf("durationMs" to (System.currentTimeMillis() - startedAtMs))
                                        )
                                    } else {
                                        LOG.warn("MavenDependencyService not available on project: ${project.name}")
                                        UsageLogger.logInteraction(project, null, "startup_sbom_service_missing")
                                    }
                                } catch (cancelled: ProcessCanceledException) {
                                    UsageLogger.logInteraction(
                                        project,
                                        null,
                                        "startup_sbom_cancelled",
                                        mapOf("durationMs" to (System.currentTimeMillis() - startedAtMs))
                                    )
                                    throw cancelled
                                } catch (t: Throwable) {
                                    UsageLogger.logInteraction(
                                        project,
                                        null,
                                        "startup_sbom_failed",
                                        mapOf(
                                            "error" to (t.message ?: t.javaClass.simpleName),
                                            "durationMs" to (System.currentTimeMillis() - startedAtMs)
                                        )
                                    )
                                    LOG.error("Error during SBOM generation", t)
                                }
                            }
                        })
                    } else {
                        UsageLogger.logInteraction(project, null, "startup_sbom_already_present", mapOf("path" to sbomFile.absolutePath))
                    }
                }
            }
        } catch (t: Throwable) {
            UsageLogger.logInteraction(project, null, "project_activity_failed", mapOf("error" to (t.message ?: t.javaClass.simpleName)))
            LOG.error("Unexpected error in ProjectStartupActivity", t)
        }

    }

    private fun surveyDir(project: Project): java.io.File {
        val base = project.basePath ?: return java.io.File(".")
        return java.io.File(base, ".license-tool")
    }

    private fun surveyFile(project: Project): java.io.File =
        java.io.File(surveyDir(project), "license-survey.json")

    private fun ensureSurveyDir(project: Project) {
        val dir = surveyDir(project)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    /**
     * Persist the filled questionnaire JSON to .license-tool/license-survey.json
     * Returns true on success, false otherwise.
     */
    private fun saveSurveyJson(project: Project, json: String): Boolean {
        return try {
            ensureSurveyDir(project)
            val file = surveyFile(project)
            file.writeText(json.trim() + System.lineSeparator(), Charsets.UTF_8)
            LOG.info("License survey saved to: ${file.absolutePath}")
            true
        } catch (t: Throwable) {
            LOG.error("Failed to save license survey: ${t.message}")
            false
        }
    }


    @RequiresEdt
    //private fun showQuestionnaireEdt(project: Project, toolWindow: MyToolWindowFactory?) {
    private fun showQuestionnaireEdt(project: Project) {
        val dialog = LicenseQuestionnaireDialog(project)
        dialog.show() // modal: returns when closed

        // After closing, check if the JSON has been created
        val created = java.io.File(project.basePath, ".license-tool/license-survey.json").exists()
        if (created) {
            LOG.info("License survey created. Submit button enabled in the tool window.")
            UsageLogger.logInteraction(project, null, "survey_dialog_completed")
            // toolWindow?.enableSubmitButton()
        } else {
            LOG.info("The questionnaire has not been saved by the user.")
            UsageLogger.logInteraction(project, null, "survey_dialog_closed_without_save")
        }
    }
}
