package com.example.my_plugin

import com.intellij.openapi.project.Project
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

object ChatSessionStore {
    data class StoredMessage(
        val role: String,
        val text: String,
        val model: String? = null,
        val ts: Long = System.currentTimeMillis()
    )

    data class StoredSession(
        val id: String,
        val createdAt: Long,
        val updatedAt: Long,
        val model: String,
        val messages: List<StoredMessage>
    ) {
        fun title(): String {
            val firstUser = messages.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
            if (firstUser.isBlank()) return "Session ${id.takeLast(6)}"
            return if (firstUser.length <= 48) firstUser else firstUser.take(45) + "..."
        }
    }

    private fun projectDir(project: Project): Path =
        project.basePath?.let { Paths.get(it) } ?: Paths.get(".")

    private fun licenseToolDir(project: Project): Path = projectDir(project).resolve(".license-tool")

    private fun sessionsDir(project: Project): Path = licenseToolDir(project).resolve("chat-sessions")

    private fun activeSessionFile(project: Project): Path = sessionsDir(project).resolve("active-session.txt")

    private fun sessionFile(project: Project, sessionId: String): Path = sessionsDir(project).resolve("$sessionId.json")

    private fun ensureSessionsDir(project: Project) {
        Files.createDirectories(sessionsDir(project))
    }

    fun createSession(project: Project, model: String): StoredSession {
        ensureSessionsDir(project)
        val now = System.currentTimeMillis()
        val session = StoredSession(
            id = "${Instant.ofEpochMilli(now).toString().replace(":", "").replace(".", "")}-${UUID.randomUUID().toString().substring(0, 8)}",
            createdAt = now,
            updatedAt = now,
            model = model,
            messages = emptyList()
        )
        saveSession(project, session)
        setActiveSession(project, session.id)
        return session
    }

    fun loadActiveSession(project: Project): StoredSession? {
        val activeId = loadActiveSessionId(project) ?: return null
        return loadSession(project, activeId)
    }

    fun loadActiveSessionId(project: Project): String? {
        val file = activeSessionFile(project)
        return try {
            if (Files.exists(file)) Files.readString(file, StandardCharsets.UTF_8).trim().ifEmpty { null } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun setActiveSession(project: Project, sessionId: String) {
        ensureSessionsDir(project)
        Files.writeString(
            activeSessionFile(project),
            sessionId,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    fun loadSession(project: Project, sessionId: String): StoredSession? {
        val file = sessionFile(project, sessionId)
        return try {
            if (!Files.exists(file)) return null
            fromJson(JSONObject(Files.readString(file, StandardCharsets.UTF_8)))
        } catch (_: Throwable) {
            null
        }
    }

    fun listSessions(project: Project): List<StoredSession> {
        val dir = sessionsDir(project)
        if (!Files.exists(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                val sessions = mutableListOf<StoredSession>()
                stream
                    .filter { it.fileName.toString().endsWith(".json") }
                    .forEach { path ->
                        try {
                            sessions += fromJson(JSONObject(Files.readString(path, StandardCharsets.UTF_8)))
                        } catch (_: Throwable) {
                            // skip malformed session files
                        }
                    }
                sessions.sortedByDescending { it.updatedAt }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveSession(project: Project, session: StoredSession) {
        ensureSessionsDir(project)
        Files.writeString(
            sessionFile(project, session.id),
            toJson(session).toString(2),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    fun appendMessage(project: Project, sessionId: String, message: StoredMessage, currentModel: String) {
        val current = loadSession(project, sessionId)
            ?: StoredSession(sessionId, message.ts, message.ts, currentModel, emptyList())
        saveSession(
            project,
            current.copy(
                updatedAt = message.ts,
                model = currentModel,
                messages = current.messages + message
            )
        )
    }

    fun replaceMessages(project: Project, sessionId: String, messages: List<StoredMessage>, currentModel: String) {
        val current = loadSession(project, sessionId)
            ?: StoredSession(sessionId, System.currentTimeMillis(), System.currentTimeMillis(), currentModel, emptyList())
        val updatedAt = messages.maxOfOrNull { it.ts } ?: System.currentTimeMillis()
        saveSession(
            project,
            current.copy(
                updatedAt = updatedAt,
                model = currentModel,
                messages = messages
            )
        )
    }

    fun updateSessionModel(project: Project, sessionId: String, currentModel: String) {
        val current = loadSession(project, sessionId) ?: return
        saveSession(project, current.copy(updatedAt = System.currentTimeMillis(), model = currentModel))
    }

    private fun toJson(session: StoredSession): JSONObject {
        val messages = JSONArray()
        session.messages.forEach { msg ->
            messages.put(
                JSONObject()
                    .put("role", msg.role)
                    .put("text", msg.text)
                    .put("model", msg.model)
                    .put("ts", msg.ts)
            )
        }
        return JSONObject()
            .put("id", session.id)
            .put("createdAt", session.createdAt)
            .put("updatedAt", session.updatedAt)
            .put("model", session.model)
            .put("messages", messages)
    }

    private fun fromJson(root: JSONObject): StoredSession {
        val messages = mutableListOf<StoredMessage>()
        val rawMessages = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until rawMessages.length()) {
            val msg = rawMessages.optJSONObject(i) ?: continue
            messages += StoredMessage(
                role = msg.optString("role", "bot"),
                text = msg.optString("text", ""),
                model = msg.optString("model", "").ifBlank { null },
                ts = msg.optLong("ts", System.currentTimeMillis())
            )
        }
        return StoredSession(
            id = root.optString("id"),
            createdAt = root.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = root.optLong("updatedAt", System.currentTimeMillis()),
            model = root.optString("model", "gpt-4o-mini"),
            messages = messages
        )
    }
}
