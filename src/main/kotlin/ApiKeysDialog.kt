package com.example.my_plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import javax.swing.Action
import javax.swing.AbstractAction
import java.net.URI
import javax.swing.JToggleButton
import javax.swing.JComponent
import javax.swing.SwingUtilities
import controller.LicensingController

// Simple dialog to configure API keys / endpoints
class ApiKeysDialog(private val project: Project) : DialogWrapper(project, true) {
    private val openAiKeyField = JBPasswordField()
    private val ollamaHostField = JBTextField()
    private val timeoutSecondsField = JBTextField()
    private val defaultEchoChar: Char = openAiKeyField.echoChar

    companion object {
        // Use the shared initializer so logs go to the per-project file configured by LogInitializer
        private val LOG = LogInitializer.getLogger(ApiKeysDialog::class.java)
    }

    // Utility to mask API keys for logging (never log full secret)
    private fun maskKey(key: String): String {
        if (key.length <= 8) return "****"
        return key.take(4) + "..." + key.takeLast(4)
    }

    init {
        title = "Settings"
        // Prefill from saved values off the EDT to avoid slow operations warnings
        loadOpenAiKeyAsync()
        ollamaHostField.text = ModelSettings.getOllamaHost(project)
        timeoutSecondsField.text = ModelSettings.getRequestTimeoutSeconds(project).toString()
        init()
        UsageLogger.logInteraction(project, null, "api_keys_dialog_opened")
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : AbstractAction("Restore Defaults") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                openAiKeyField.text = ""
                ollamaHostField.text = ""
                timeoutSecondsField.text = ModelSettings.defaultRequestTimeoutSeconds(project).toString()
                UsageLogger.logInteraction(
                    project,
                    null,
                    "api_settings_restore_defaults_clicked",
                    mapOf("selectedModel" to ModelSettings.getSelectedModel(project))
                )
            }
        }
    )

    override fun createCenterPanel(): JComponent {
        timeoutSecondsField.columns = 8
        val root = panel {
            group("OpenAI") {
                row("API Key") {
                    cell(openAiKeyField).resizableColumn().align(Align.FILL)
                    val eyeToggle = JToggleButton(AllIcons.Actions.Preview).apply {
                        toolTipText = "Show/Hide"
                        isContentAreaFilled = false
                        isBorderPainted = false
                        addActionListener {
                            if (isSelected) {
                                openAiKeyField.echoChar = 0.toChar()
                                toolTipText = "Hide"
                            } else {
                                openAiKeyField.echoChar = defaultEchoChar
                                toolTipText = "Show"
                            }
                        }
                    }
                    cell(eyeToggle)
                }
            }
            group("Ollama (Llama models)") {
                row("Host") {
                    cell(ollamaHostField).resizableColumn().align(Align.FILL)
                        .comment("Example: http://127.0.0.1:11434")
                }
            }
            group("Network") {
                row("Request timeout (s)") {
                    cell(timeoutSecondsField)
                        .comment("Used as the HTTP request timeout for model calls. Default depends on the selected provider.")
                }
            }
        }
        root.preferredSize = com.intellij.util.ui.JBUI.size(580, 300)
        root.minimumSize = com.intellij.util.ui.JBUI.size(560, 280)
        return root
    }

    override fun doValidate(): ValidationInfo? {
        val selectedModel = ModelSettings.getSelectedModel(project)
        val provider = ModelSettings.getProviderForModel(selectedModel)
        if (provider == ModelProvider.OPENAI && openAiKeyField.password.isEmpty()) {
            return ValidationInfo("Set the API key to use the OpenAI-based assistant.")
        }
        if (provider == ModelProvider.OLLAMA) {
            val host = ollamaHostField.text.trim()
            if (host.isEmpty()) {
                return ValidationInfo("Set the Ollama host for Ollama-based models.", ollamaHostField)
            }
            if (!isValidOllamaHost(host)) {
                return ValidationInfo("Insert a valid Ollama host (example: http://192.168.0.157:11434).", ollamaHostField)
            }
        }
        val timeoutSeconds = timeoutSecondsField.text.trim().toIntOrNull()
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return ValidationInfo("Insert a valid timeout in seconds.", timeoutSecondsField)
        }
        return null
    }

    override fun doOKAction() {
        val openAiKey = String(openAiKeyField.password)
        val ollamaHost = ollamaHostField.text.trim()
        val timeoutSeconds = timeoutSecondsField.text.trim().toInt()
        val selectedModel = ModelSettings.getSelectedModel(project)

        ApplicationManager.getApplication().executeOnPooledThread {
            ApiKeyStore.setOpenAiKey(project, openAiKey)
            ModelSettings.setOllamaHost(project, ollamaHost)
            ModelSettings.setRequestTimeoutSeconds(project, timeoutSeconds)
            project.getService(LicensingController::class.java)?.resetChatbotSession()
            LOG.info("Saved OpenAI key for project ${project.name} — masked=${maskKey(openAiKey)}")
            LogInitializer.logState(
                this::class.java,
                project,
                "api_settings_saved",
                mapOf(
                    "masked" to maskKey(openAiKey),
                    "ollamaHostSet" to ollamaHost.isNotBlank().toString(),
                    "requestTimeoutSeconds" to timeoutSeconds.toString()
                )
            )
            UsageLogger.logInteraction(
                project,
                null,
                "api_settings_saved",
                mapOf(
                    "openAiKeyConfigured" to openAiKey.isNotBlank(),
                    "ollamaHost" to ollamaHost,
                    "selectedModel" to selectedModel,
                    "requestTimeoutSeconds" to timeoutSeconds
                )
            )

            SwingUtilities.invokeLater {
                // enable UI after saving
                MyToolWindowBridge.getInstance(project).ui?.apply {
                    enableSubmitButton()
                    enableInputArea()
                    refreshConnectionIndicator()
                }
            }
        }

        super.doOKAction()
    }

    override fun doCancelAction() {
        // Cancel/close should only dismiss the dialog and keep the current key unchanged.
        UsageLogger.logInteraction(project, null, "api_keys_dialog_cancelled")
        super.doCancelAction()
    }

    private fun loadOpenAiKeyAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = ApiKeyStore.getOpenAiKey(project) ?: ""
            SwingUtilities.invokeLater {
                if (openAiKeyField.isDisplayable) {
                    openAiKeyField.text = key
                }
            }
        }
    }

    private fun isValidOllamaHost(raw: String): Boolean {
        val normalized = raw.trim()
        if (normalized.isBlank() || normalized.contains(' ')) return false
        val withScheme = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "http://$normalized"
        }
        return try {
            val uri = URI(withScheme)
            val host = uri.host
            val port = uri.port
            host != null && host.isNotBlank() && (port == -1 || (port in 1..65535))
        } catch (_: Throwable) {
            false
        }
    }
}
