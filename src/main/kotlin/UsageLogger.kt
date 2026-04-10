package com.example.my_plugin

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

object UsageLogger {
    private val lock = ReentrantLock()
    private val currentSessionIds = ConcurrentHashMap<String, String>()

    private fun projectDir(project: Project): Path =
        project.basePath?.let { Paths.get(it) } ?: Paths.get(".")

    private fun licenseToolDir(project: Project): Path = projectDir(project).resolve(".license-tool")

    private fun logDir(project: Project): Path = licenseToolDir(project).resolve("log")

    private fun usageLogFile(project: Project): Path = logDir(project).resolve("usage.log")
    private fun interactionLogFile(project: Project): Path = logDir(project).resolve("interaction-log.jsonl")

    private fun userIdFile(project: Project): Path = licenseToolDir(project).resolve("user-id.txt")

    fun loadUserId(project: Project): String? {
        val f = userIdFile(project)
        return try {
            if (Files.exists(f)) {
                Files.readString(f, StandardCharsets.UTF_8).trim().ifEmpty { null }
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun saveUserId(project: Project, userId: String) {
        lock.withLock {
            try {
                val dir = licenseToolDir(project)
                if (!Files.exists(dir)) Files.createDirectories(dir)
                Files.writeString(userIdFile(project), userId.trim(), StandardCharsets.UTF_8)
                // Also log the event
                logEventInternal(project, userId.trim(), "user_id_saved", null)
            } catch (_: Throwable) {
                // ignore failures
            }
        }
    }

    fun logEvent(project: Project, userId: String?, event: String, extra: Map<String, Any?>? = null) {
        lock.withLock {
            logEventInternal(project, userId ?: loadUserId(project) ?: "unknown", event, extra)
        }
    }

    fun setCurrentSessionId(project: Project, sessionId: String?) {
        val key = project.basePath ?: project.name
        if (sessionId.isNullOrBlank()) {
            currentSessionIds.remove(key)
        } else {
            currentSessionIds[key] = sessionId
        }
    }

    private fun currentSessionId(project: Project): String? {
        val key = project.basePath ?: project.name
        return currentSessionIds[key]
    }

    fun logInteraction(project: Project, userId: String?, type: String, payload: Map<String, Any?>? = null) {
        lock.withLock {
            try {
                val dir = logDir(project)
                if (!Files.exists(dir)) Files.createDirectories(dir)
                val out = interactionLogFile(project)
                val obj = JSONObject().apply {
                    put("ts", Instant.now().toEpochMilli())
                    put("project", project.name)
                    put("userId", userId ?: loadUserId(project) ?: "unknown")
                    currentSessionId(project)?.let { put("sessionId", it) }
                    put("type", type)
                    if (payload != null) put("payload", JSONObject(payload))
                }
                Files.writeString(
                    out,
                    obj.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            } catch (_: Throwable) {
                // ignore logging failures
            }
        }
    }

    private fun logEventInternal(project: Project, userId: String, event: String, extra: Map<String, Any?>? = null) {
        try {
            val dir = logDir(project)
            if (!Files.exists(dir)) Files.createDirectories(dir)
            val out = usageLogFile(project)
            val obj = JSONObject().apply {
                put("ts", Instant.now().toEpochMilli())
                put("project", project.name)
                put("userId", userId)
                currentSessionId(project)?.let { put("sessionId", it) }
                put("event", event)
                if (extra != null) put("extra", JSONObject(extra))
            }
            val line = obj.toString() + System.lineSeparator()
            Files.writeString(out, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Throwable) {
            // ignore logging failures
        }
    }
}
