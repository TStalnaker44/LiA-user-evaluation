package com.example.my_plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

enum class ModelProvider {
    OPENAI,
    OLLAMA
}

object ModelSettings {
    data class ModelDescriptor(val id: String, val provider: ModelProvider)

    private const val KEY_SELECTED_MODEL = "license_tool.selected_model"
    private const val KEY_OLLAMA_HOST = "license_tool.ollama_host"
    private const val KEY_REQUEST_TIMEOUT_SECONDS = "license_tool.request_timeout_seconds"
    private const val DEFAULT_MODEL = "gpt-5.2"
    private const val DEFAULT_OLLAMA_HOST = ""
    private const val DEFAULT_OPENAI_TIMEOUT_SECONDS = 90
    private const val DEFAULT_OLLAMA_TIMEOUT_SECONDS = 30
    private val AVAILABLE_MODELS = listOf(
        ModelDescriptor("llama3.2:latest", ModelProvider.OLLAMA),
        ModelDescriptor("gpt-oss:20b-cloud", ModelProvider.OLLAMA),
        ModelDescriptor("gpt-oss:120b-cloud", ModelProvider.OLLAMA),
        ModelDescriptor("gpt-4o-mini", ModelProvider.OPENAI),
        ModelDescriptor("gpt-4o", ModelProvider.OPENAI),
        ModelDescriptor("gpt-5-mini", ModelProvider.OPENAI),
        ModelDescriptor("gpt-5", ModelProvider.OPENAI),
        ModelDescriptor("gpt-5.1", ModelProvider.OPENAI),
        ModelDescriptor("gpt-5.2", ModelProvider.OPENAI)
    )

    fun getSelectedModel(project: Project): String {
        val raw = PropertiesComponent.getInstance(project).getValue(KEY_SELECTED_MODEL, DEFAULT_MODEL)
        return normalizeModelId(raw)
    }

    fun setSelectedModel(project: Project, model: String) {
        PropertiesComponent.getInstance(project).setValue(KEY_SELECTED_MODEL, normalizeModelId(model))
    }

    fun availableModels(provider: ModelProvider): List<String> =
        AVAILABLE_MODELS.filter { it.provider == provider }.map { it.id }

    fun getProviderForModel(model: String): ModelProvider {
        val normalized = normalizeModelId(model)
        AVAILABLE_MODELS.firstOrNull { it.id.equals(normalized, ignoreCase = true) }?.let { return it.provider }
        val lower = normalized.lowercase()
        return when {
            lower.startsWith("gpt-oss:") -> ModelProvider.OLLAMA
            lower.startsWith("llama") -> ModelProvider.OLLAMA
            lower.startsWith("gpt-4o") -> ModelProvider.OPENAI
            lower.startsWith("gpt-5") -> ModelProvider.OPENAI
            lower.startsWith("o1") || lower.startsWith("o3") -> ModelProvider.OPENAI
            else -> ModelProvider.OLLAMA
        }
    }

    private fun normalizeModelId(model: String): String {
        val trimmed = model.trim()
        val lower = trimmed.lowercase()
        return when (lower) {
            "gpt5" -> "gpt-5"
            "gpt5.1" -> "gpt-5.1"
            "gpt5.2" -> "gpt-5.2"
            else -> trimmed
        }
    }

    fun getOllamaHost(project: Project): String {
        val appProperties = PropertiesComponent.getInstance()
        val globalHost = appProperties.getValue(KEY_OLLAMA_HOST, DEFAULT_OLLAMA_HOST).trim()
        if (globalHost.isNotBlank()) {
            return globalHost
        }

        val projectHost = PropertiesComponent.getInstance(project).getValue(KEY_OLLAMA_HOST, DEFAULT_OLLAMA_HOST).trim()
        if (projectHost.isNotBlank()) {
            appProperties.setValue(KEY_OLLAMA_HOST, projectHost)
            return projectHost
        }
        return ""
    }

    fun setOllamaHost(project: Project, host: String) {
        val normalized = host.trim()
        PropertiesComponent.getInstance().setValue(KEY_OLLAMA_HOST, normalized)
        PropertiesComponent.getInstance(project).setValue(KEY_OLLAMA_HOST, normalized)
    }

    fun getRequestTimeoutSeconds(project: Project): Int {
        val appProperties = PropertiesComponent.getInstance()
        val globalValue = parseTimeout(appProperties.getValue(KEY_REQUEST_TIMEOUT_SECONDS))
        if (globalValue != null) {
            return globalValue
        }

        val projectValue = parseTimeout(PropertiesComponent.getInstance(project).getValue(KEY_REQUEST_TIMEOUT_SECONDS))
        if (projectValue != null) {
            appProperties.setValue(KEY_REQUEST_TIMEOUT_SECONDS, projectValue, defaultRequestTimeoutSeconds(project))
            return projectValue
        }
        return defaultRequestTimeoutSeconds(project)
    }

    fun setRequestTimeoutSeconds(project: Project, seconds: Int) {
        val normalized = seconds.coerceAtLeast(1)
        PropertiesComponent.getInstance().setValue(KEY_REQUEST_TIMEOUT_SECONDS, normalized, defaultRequestTimeoutSeconds(project))
        PropertiesComponent.getInstance(project).setValue(KEY_REQUEST_TIMEOUT_SECONDS, normalized.toString())
    }

    fun defaultRequestTimeoutSeconds(project: Project): Int {
        return when (getProviderForModel(getSelectedModel(project))) {
            ModelProvider.OPENAI -> DEFAULT_OPENAI_TIMEOUT_SECONDS
            ModelProvider.OLLAMA -> DEFAULT_OLLAMA_TIMEOUT_SECONDS
        }
    }

    private fun parseTimeout(value: String?): Int? {
        val parsed = value?.trim()?.toIntOrNull() ?: return null
        return parsed.takeIf { it > 0 }
    }
}
