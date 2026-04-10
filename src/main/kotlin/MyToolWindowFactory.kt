package com.example.my_plugin

import com.intellij.ide.ui.LafManagerListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.openapi.ui.Messages
import controller.LicensingController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import com.example.my_plugin.license.LicenseQuestionnaireDialog
import javax.swing.Timer
import com.intellij.openapi.observable.properties.PropertyGraph
import javax.swing.event.HyperlinkEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.json.JSONObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger

class MyToolWindowFactory : ToolWindowFactory {
    companion object {
        private val SEND_ICON = IconLoader.getIcon("/icons/sendIcon.svg", MyToolWindowFactory::class.java)
    }

    // Encapsulate per-project UI and state
    class ChatUi(private val project: Project) : Disposable {
        private enum class ConnectionState {
            UNKNOWN,
            CHECKING,
            OK,
            ERROR
        }

        var chatView: JEditorPane? = null
        var inputArea: JBTextArea? = null
        var loaderLabel: JBLabel? = null
        var submitButton: JButton? = null
        private var modelSelectorButton: JButton? = null
        private var modelSelectorLabel: JBLabel? = null
        private var connectionStatusLabel: JBLabel? = null
        private var loadingTimer: Timer? = null
        private var loadingDepth: Int = 0
        private val propertyGraph = PropertyGraph()
        val selectedModelProp = propertyGraph.property(ModelSettings.getSelectedModel(project))
        private val inputPlaceholder = "Ask about any license issues of your project"
        private var inputPlaceholderVisible = false
        private var inputTextColor: Color? = null

        // Add a reference to the Java listener so we can unregister it on dispose
        private var surveyListener: LicenseQuestionnaireListener? = null

        private data class ModelDropdownEntry(
            val text: String,
            val modelId: String? = null,
            val providerLabel: String? = null,
            val showSeparator: Boolean = false,
            val isAction: Boolean = false
        ) {
            override fun toString(): String = text
        }

        private data class SessionListEntry(
            val text: String,
            val sessionId: String,
            val showSeparator: Boolean = false,
            val separatorText: String? = null
        )

        private data class ChatMessage(val role: String, val text: String, val model: String? = null, val ts: Long = System.currentTimeMillis())
        private val messages = mutableListOf<ChatMessage>()
        private var currentSessionId: String? = null
        @Volatile private var pendingBackendRestore: Future<*>? = null
        private val backendRestoreGeneration = AtomicInteger(0)
        private val connectivityCheckGeneration = AtomicInteger(0)

        private val tsFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        private val sessionListFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        private val structuredReportFields = setOf(
            "status",
            "projectLicenseDetected",
            "projectLicenseIntended",
            "knownCompatible",
            "potentialIssues",
            "unknownDependencies",
            "notes"
        )
        private val structuredReportLabels = mapOf(
            "status" to "Overall Status",
            "projectLicenseDetected" to "Detected Project License",
            "projectLicenseIntended" to "Intended Project License",
            "knownCompatible" to "Compatible Dependencies",
            "potentialIssues" to "Potential Issues",
            "unknownDependencies" to "Dependencies Requiring Verification",
            "notes" to "Notes"
        )

        private val LOG: Logger = LogInitializer.getLogger(MyToolWindowFactory::class.java)

        init {
            selectedModelProp.afterChange { newValue ->
                ModelSettings.setSelectedModel(project, newValue)
                modelSelectorLabel?.text = newValue
                currentSessionId?.let { ChatSessionStore.updateSessionModel(project, it, newValue) }
                UsageLogger.logInteraction(
                    project,
                    null,
                    "model_selected",
                    mapOf(
                        "model" to newValue,
                        "provider" to ModelSettings.getProviderForModel(newValue).name
                    )
                )
                refreshConnectionIndicator()
            }
            ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener {
                SwingUtilities.invokeLater {
                    // LAF updates are async: re-render after the update cycle settles.
                    SwingUtilities.invokeLater { refreshChatHtml() }
                }
            })
        }

        private fun greetingMessage(): String =
            "Hi, I'm LiA, your Licensing Assistant. I'm here to help with licensing issues in your project."

        private fun persistMessages() {
            val sessionId = currentSessionId ?: return
            ChatSessionStore.replaceMessages(
                project,
                sessionId,
                messages.map { ChatSessionStore.StoredMessage(it.role, it.text, it.model, it.ts) },
                selectedModelProp.get()
            )
        }

        private fun invalidatePendingBackendRestore() {
            backendRestoreGeneration.incrementAndGet()
            pendingBackendRestore?.cancel(true)
            pendingBackendRestore = null
        }

        private fun scheduleBackendSessionRebuild() {
            val controller = project.getService(LicensingController::class.java)
            val selectedModel = selectedModelProp.get()
            val messageSnapshot = messages.toList()
            val generation = backendRestoreGeneration.incrementAndGet()
            pendingBackendRestore?.cancel(true)
            pendingBackendRestore = ApplicationManager.getApplication().executeOnPooledThread {
                if (generation != backendRestoreGeneration.get()) return@executeOnPooledThread
                controller.resetChatbotSession()
                if (generation != backendRestoreGeneration.get()) return@executeOnPooledThread
                val session = controller.getChatbotSession(selectedModel)
                for (message in messageSnapshot) {
                    if (generation != backendRestoreGeneration.get() || Thread.currentThread().isInterrupted) {
                        return@executeOnPooledThread
                    }
                    val historyRole = if (message.role == "user") "user" else "assistant"
                    session.addToHistory(historyRole, message.text)
                }
                if (generation == backendRestoreGeneration.get()) {
                    pendingBackendRestore = null
                }
            }
        }

        private fun awaitPendingBackendRestore() {
            val task = pendingBackendRestore ?: return
            try {
                task.get()
            } catch (_: Throwable) {
                // Ignore. The active request path will rebuild or recreate the session if needed.
            } finally {
                if (pendingBackendRestore === task) {
                    pendingBackendRestore = null
                }
            }
        }

        private fun updateConnectionIndicator(state: ConnectionState, tooltip: String) {
            SwingUtilities.invokeLater {
                val label = connectionStatusLabel ?: return@invokeLater
                label.text = "\u25cf"
                label.foreground = when (state) {
                    ConnectionState.UNKNOWN -> JBColor(Color(0x8A, 0x90, 0x99), Color(0x7F, 0x86, 0x90))
                    ConnectionState.CHECKING -> JBColor(Color(0xD9, 0xA4, 0x1B), Color(0xE0, 0xB3, 0x3B))
                    ConnectionState.OK -> JBColor(Color(0x2E, 0xA0, 0x43), Color(0x57, 0xC0, 0x68))
                    ConnectionState.ERROR -> JBColor(Color(0xD1, 0x44, 0x44), Color(0xF2, 0x6D, 0x6D))
                }
                label.toolTipText = tooltip
            }
        }

        fun refreshConnectionIndicator() {
            val selectedModel = selectedModelProp.get()
            val provider = ModelSettings.getProviderForModel(selectedModel)
            val checkId = connectivityCheckGeneration.incrementAndGet()

            if (provider == ModelProvider.OPENAI) {
                updateConnectionIndicator(ConnectionState.CHECKING, "Checking OpenAI connectivity...")
                ApplicationManager.getApplication().executeOnPooledThread {
                    val apiKey = ApiKeyStore.getOpenAiKey(project)
                    if (apiKey.isNullOrBlank()) {
                        if (checkId == connectivityCheckGeneration.get()) {
                            updateConnectionIndicator(ConnectionState.ERROR, "Missing OpenAI API key")
                        }
                        return@executeOnPooledThread
                    }
                    val result = checkOpenAiConnectivity(apiKey)
                    if (checkId == connectivityCheckGeneration.get()) {
                        updateConnectionIndicator(result.first, result.second)
                    }
                }
                return
            }

            updateConnectionIndicator(ConnectionState.CHECKING, "Checking Ollama connectivity...")
            ApplicationManager.getApplication().executeOnPooledThread {
                val host = ModelSettings.getOllamaHost(project)
                if (host.isBlank()) {
                    if (checkId == connectivityCheckGeneration.get()) {
                        updateConnectionIndicator(ConnectionState.ERROR, "Missing Ollama host")
                    }
                    return@executeOnPooledThread
                }
                val result = checkOllamaConnectivity(host)
                if (checkId == connectivityCheckGeneration.get()) {
                    updateConnectionIndicator(result.first, result.second)
                }
            }
        }

        private fun checkOpenAiConnectivity(apiKey: String): Pair<ConnectionState, String> {
            return try {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
                val request = HttpRequest.newBuilder()
                    .uri(URI("https://api.openai.com/v1/models"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer $apiKey")
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                when (response.statusCode()) {
                    in 200..299 -> ConnectionState.OK to "OpenAI configured and reachable"
                    401 -> ConnectionState.ERROR to "OpenAI API key rejected (401)"
                    else -> ConnectionState.ERROR to "OpenAI endpoint returned HTTP ${response.statusCode()}"
                }
            } catch (t: Throwable) {
                ConnectionState.ERROR to "OpenAI unreachable: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        private fun checkOllamaConnectivity(host: String): Pair<ConnectionState, String> {
            val normalized = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
            return try {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
                val request = HttpRequest.newBuilder()
                    .uri(URI(normalized.trimEnd('/') + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                when (response.statusCode()) {
                    in 200..299 -> ConnectionState.OK to "Ollama configured and reachable"
                    else -> ConnectionState.ERROR to "Ollama endpoint returned HTTP ${response.statusCode()}"
                }
            } catch (t: Throwable) {
                ConnectionState.ERROR to "Ollama unreachable: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        private fun restoreSession(session: ChatSessionStore.StoredSession) {
            currentSessionId = session.id
            UsageLogger.setCurrentSessionId(project, session.id)
            if (session.model.isNotBlank() && session.model != selectedModelProp.get()) {
                selectedModelProp.set(session.model)
            }
            messages.clear()
            messages += session.messages.map { ChatMessage(it.role, it.text, it.model, it.ts) }
            refreshChatHtml()
            chatView?.caretPosition = chatView?.document?.length ?: 0
            scheduleBackendSessionRebuild()
            UsageLogger.logInteraction(
                project,
                null,
                "chat_session_resumed",
                mapOf(
                    "sessionId" to session.id,
                    "messageCount" to session.messages.size,
                    "model" to session.model
                )
            )
        }

        fun initializeSession() {
            val active = ChatSessionStore.loadActiveSession(project)
            if (active != null) {
                restoreSession(active)
                return
            }
            startNewSession()
        }

        private fun startNewSession() {
            invalidatePendingBackendRestore()
            project.getService(LicensingController::class.java).resetChatbotSession()
            val session = ChatSessionStore.createSession(project, selectedModelProp.get())
            currentSessionId = session.id
            UsageLogger.setCurrentSessionId(project, session.id)
            messages.clear()
            refreshChatHtml()
            chatView?.caretPosition = 0
            UsageLogger.logInteraction(
                project,
                null,
                "chat_session_started",
                mapOf("sessionId" to session.id, "model" to selectedModelProp.get())
            )
            appendToChatHistory(greetingMessage())
        }

        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        private fun renderInlineMarkdown(src: String): String {
            var text = escapeHtml(src)
            text = text.replace(Regex("`([^`]+)`")) { m -> "<span class='code'>" + m.groupValues[1] + "</span>" }
            text = text.replace(Regex("\\*\\*([^*]+)\\*\\*")) { m -> "<b>" + m.groupValues[1] + "</b>" }
            text = text.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { m -> "<i>" + m.groupValues[1] + "</i>" }
            return text
        }

        private fun normalizeMarkdownLikeText(src: String): String {
            var text = src.replace("\r\n", "\n").replace('\r', '\n')
            // Split common inline "sub-bullets" into proper lines.
            text = text.replace(Regex(":\\s+-\\s+"), ":\n  - ")
            text = text.replace(Regex("(?<=\\S)\\s+-\\s+(?=[A-Z])"), "\n  - ")
            return text
        }

        private fun isStructuredFieldLabel(label: String): Boolean = label.trim() in structuredReportFields

        private fun displayStructuredFieldLabel(label: String): String =
            structuredReportLabels[label.trim()] ?: label.trim()

        private fun looksLikeStructuredReport(src: String): Boolean {
            val matches = src.lineSequence()
                .map { it.trim() }
                .count {
                    val m = Regex("^([A-Za-z][A-Za-z0-9]+):\\s*(.*)$").matchEntire(it) ?: return@count false
                    isStructuredFieldLabel(m.groupValues[1])
                }
            return matches >= 2
        }

        private fun normalizeStructuredReportText(src: String): String {
            val orderedFields = listOf(
                "status",
                "projectLicenseDetected",
                "projectLicenseIntended",
                "knownCompatible",
                "potentialIssues",
                "unknownDependencies",
                "notes"
            )
            val trimmed = src.trim()
            val candidate = if (trimmed.startsWith("```")) {
                trimmed
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
            } else {
                trimmed
            }
            val jsonText = when {
                candidate.startsWith("{") && candidate.endsWith("}") -> candidate
                else -> {
                    val start = candidate.indexOf('{')
                    val end = candidate.lastIndexOf('}')
                    if (start >= 0 && end > start) {
                        candidate.substring(start, end + 1)
                    } else {
                        return src
                    }
                }
            }
            return try {
                val root = JSONObject(jsonText)
                if (orderedFields.none { root.has(it) }) return src
                buildString {
                    orderedFields.forEach { field ->
                        if (!root.has(field)) return@forEach
                        append(field).append(":")
                        val value = root.get(field)
                        when (value) {
                            is org.json.JSONArray -> {
                                if (value.length() == 0) {
                                    append(" none")
                                } else {
                                    for (i in 0 until value.length()) {
                                        val item = value.opt(i)?.toString()?.trim().orEmpty()
                                        append("\n- ").append(item.removePrefix("- ").trim())
                                    }
                                }
                            }
                            JSONObject.NULL -> append(" UNKNOWN")
                            else -> append(" ").append(value.toString().trim())
                        }
                        append("\n")
                    }
                }.trim()
            } catch (_: Throwable) {
                src
            }
        }

        private fun markdownToHtmlBasic(src: String): String {
            val codeBlocks = mutableListOf<String>()
            var text = normalizeMarkdownLikeText(src)
            val fenceRegex = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
            text = fenceRegex.replace(text) { m ->
                val idx = codeBlocks.size
                codeBlocks += stripFenceInfoString(m.groupValues[1])
                "@@CODEBLOCK_$idx@@"
            }
            val lines = text.split("\n")
            val out = StringBuilder()
            var inList = false
            var inOrderedList = false
            var currentListIndent = 0
            var openListItem = false
            val paragraph = StringBuilder()

            fun flushParagraph() {
                if (paragraph.isEmpty()) return
                out.append("<p>").append(paragraph.toString()).append("</p>")
                paragraph.setLength(0)
            }

            fun closeOpenListItem() {
                if (openListItem) {
                    out.append("</li>")
                    openListItem = false
                }
            }

            fun closeLists() {
                closeOpenListItem()
                if (inList) {
                    out.append("</ul>")
                    inList = false
                }
                if (inOrderedList) {
                    out.append("</ol>")
                    inOrderedList = false
                }
                currentListIndent = 0
            }

            var index = 0
            while (index < lines.size) {
                val ln = lines[index]
                val trimmed = ln.trim()
                if (trimmed.isEmpty()) {
                    flushParagraph()
                    closeLists()
                    index += 1
                    continue
                }

                if (Regex("@@CODEBLOCK_\\d+@@").matches(trimmed)) {
                    flushParagraph()
                    closeLists()
                    out.append(trimmed)
                    index += 1
                    continue
                }

                if (isMarkdownTableStart(lines, index)) {
                    flushParagraph()
                    closeLists()
                    val consumed = appendMarkdownTable(lines, index, out)
                    index += consumed
                    continue
                }

                when {
                    trimmed.startsWith("### ") -> {
                        flushParagraph()
                        closeLists()
                        out.append("<div class='md-h3'>").append(renderInlineMarkdown(trimmed.removePrefix("### ").trim())).append("</div>")
                    }
                    trimmed.startsWith("## ") -> {
                        flushParagraph()
                        closeLists()
                        out.append("<div class='md-h2'>").append(renderInlineMarkdown(trimmed.removePrefix("## ").trim())).append("</div>")
                    }
                    trimmed.startsWith("# ") -> {
                        flushParagraph()
                        closeLists()
                        out.append("<div class='md-h1'>").append(renderInlineMarkdown(trimmed.removePrefix("# ").trim())).append("</div>")
                    }
                    Regex("^([A-Za-z][A-Za-z0-9]+):\\s*(.*)$").matches(trimmed) -> {
                        val match = Regex("^([A-Za-z][A-Za-z0-9]+):\\s*(.*)$").matchEntire(trimmed)
                        val label = match?.groupValues?.getOrNull(1).orEmpty()
                        val value = match?.groupValues?.getOrNull(2).orEmpty()
                        if (isStructuredFieldLabel(label)) {
                            flushParagraph()
                            closeLists()
                            out.append("<div class='report-field'><span class='report-label'>")
                                .append(escapeHtml(displayStructuredFieldLabel(label)))
                                .append(":</span>")
                            if (value.isNotBlank()) {
                                out.append(" <span class='report-value'>")
                                    .append(renderInlineMarkdown(value))
                                    .append("</span>")
                            }
                            out.append("</div>")
                        } else {
                            closeLists()
                            if (paragraph.isNotEmpty()) {
                                paragraph.append("<br>")
                            }
                            paragraph.append(renderInlineMarkdown(trimmed))
                        }
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        flushParagraph()
                        val indent = ln.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        if (inOrderedList || !inList || currentListIndent != indent) {
                            closeLists()
                            out.append("<ul class='md-list indent-$indent'>")
                            inList = true
                            currentListIndent = indent
                        }
                        closeOpenListItem()
                        out.append("<li>").append(renderInlineMarkdown(trimmed.substring(2).trim()))
                        openListItem = true
                    }
                    Regex("^\\d+[.)]\\s+.+$").matches(trimmed) -> {
                        flushParagraph()
                        val indent = ln.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        if (inList || !inOrderedList || currentListIndent != indent) {
                            closeLists()
                            out.append("<ol class='md-list indent-$indent'>")
                            inOrderedList = true
                            currentListIndent = indent
                        }
                        closeOpenListItem()
                        val itemText = trimmed.replaceFirst(Regex("^\\d+[.)]\\s+"), "")
                        out.append("<li>").append(renderInlineMarkdown(itemText.trim()))
                        openListItem = true
                    }
                    else -> {
                        if (openListItem) {
                            out.append("<br>").append(renderInlineMarkdown(trimmed))
                        } else {
                            closeLists()
                            if (paragraph.isNotEmpty()) {
                                paragraph.append("<br>")
                            }
                            paragraph.append(renderInlineMarkdown(trimmed))
                        }
                    }
                }
                index += 1
            }
            flushParagraph()
            closeLists()
            text = out.toString()

            // Replace placeholders with escaped code blocks (use local vars so static analysis sees usage)
            codeBlocks.forEachIndexed { idx, raw ->
                val replaced = "<pre>" + escapeHtml(raw.trim()) + "</pre>"
                text = text.replace("@@CODEBLOCK_${idx}@@", replaced)
            }
            return text
        }

        private fun isMarkdownTableStart(lines: List<String>, index: Int): Boolean {
            if (index + 1 >= lines.size) return false
            val header = lines[index].trim()
            val separator = lines[index + 1].trim()
            return header.contains('|') && isMarkdownTableSeparator(separator)
        }

        private fun isMarkdownTableSeparator(line: String): Boolean {
            if (!line.contains('|')) return false
            val trimmed = line.trim().trim('|')
            if (trimmed.isBlank()) return false
            return trimmed.split('|').all { part ->
                val cell = part.trim()
                cell.isNotEmpty() && cell.all { it == '-' || it == ':' }
            }
        }

        private fun splitMarkdownTableCells(line: String): List<String> =
            line.trim()
                .trim('|')
                .split('|')
                .map { it.trim() }

        private fun appendMarkdownTable(lines: List<String>, startIndex: Int, out: StringBuilder): Int {
            val headerCells = splitMarkdownTableCells(lines[startIndex])
            if (headerCells.isEmpty()) return 1

            val bodyRows = mutableListOf<List<String>>()
            var index = startIndex + 2
            while (index < lines.size) {
                val candidate = lines[index].trim()
                if (candidate.isEmpty() || !candidate.contains('|')) break
                if (Regex("@@CODEBLOCK_\\d+@@").matches(candidate)) break
                bodyRows += splitMarkdownTableCells(lines[index])
                index += 1
            }

            out.append("<div class='md-table-wrap'><table class='md-table'><thead><tr>")
            headerCells.forEach { cell ->
                out.append("<th>").append(renderInlineMarkdown(cell)).append("</th>")
            }
            out.append("</tr></thead>")
            if (bodyRows.isNotEmpty()) {
                out.append("<tbody>")
                bodyRows.forEach { row ->
                    out.append("<tr>")
                    headerCells.indices.forEach { columnIndex ->
                        val cell = row.getOrElse(columnIndex) { "" }
                        out.append("<td>").append(renderInlineMarkdown(cell)).append("</td>")
                    }
                    out.append("</tr>")
                }
                out.append("</tbody>")
            }
            out.append("</table></div>")
            return index - startIndex
        }

        private fun stripFenceInfoString(rawBlock: String): String {
            val normalized = rawBlock.removePrefix("\r").removePrefix("\n")
            val firstLineEnd = normalized.indexOf('\n')
            if (firstLineEnd <= 0) {
                return normalized
            }
            val infoString = normalized.substring(0, firstLineEnd).trim()
            return if (infoString.matches(Regex("[A-Za-z0-9_+-]+"))) {
                normalized.substring(firstLineEnd + 1)
            } else {
                normalized
            }
        }

        private fun toHex(color: Color): String {
            return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
        }

        private fun uiColorHex(key: String, fallback: Color): String {
            return toHex(UIManager.getColor(key) ?: fallback)
        }

        private fun luminance(color: Color): Double {
            return 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
        }

        private fun isDarkThemeNow(): Boolean {
            val base = UIManager.getColor("EditorPane.background")
                ?: UIManager.getColor("Panel.background")
                ?: Color(0xF6, 0xF7, 0xF8)
            return luminance(base) < 140
        }

        private fun refreshChatHtml() {
            chatView?.text = renderChatHtml()
            chatView?.caretPosition = chatView?.document?.length ?: 0
        }

        private fun renderChatHtml(): String {
            val dark = isDarkThemeNow()
            val panelColor = UIManager.getColor("Panel.background")
                ?: if (dark) Color(0x2B, 0x2D, 0x30) else Color(0xF6, 0xF7, 0xF8)
            val bodyBg = toHex(panelColor)
            val textColor = if (dark) uiColorHex("Label.foreground", Color(0xD6, 0xDB, 0xE3))
                else uiColorHex("Label.foreground", Color(0x2B, 0x2D, 0x30))
            val borderColor = if (dark) "#4a5361" else "#d7dbe0"
            val linkColor = if (dark) "#7db5ff" else "#0b6eff"
            val userBg = if (dark) "#1f4160" else "#eaf3ff"
            val botBg = if (dark) "#262c36" else "#ffffff"
            val codeBg = if (dark) "#1d232c" else "#f8f9fb"
            val metaColor = if (dark) "#a6b0bf" else "#6f7785"
            val userNameColor = if (dark) "#c2e0ff" else "#0b3d62"
            val botNameColor = if (dark) "#e2e7ef" else "#2b2d30"
            val userAvatarBg = if (dark) "#2f79bd" else "#0b6eff"
            val botAvatarBg = if (dark) "#6b7586" else "#6b6f76"

            val sb = StringBuilder()
            sb.append(
                """
                <html>
                <head>
                  <style>
                    body { font-family: sans-serif; font-size: 12px; margin: 0; background: $bodyBg; color: $textColor; }
                    .wrap { padding: 8px; }
                    .msg { padding: 8px 10px; margin: 6px 0; border: 1px solid $borderColor; }
                    .user  { background: $userBg; }
                    .bot   { background: $botBg; }
                    p { margin: 0 0 8px 0; }
                    ul, ol { margin: 6px 0 8px 18px; padding-left: 18px; }
                    li { margin: 2px 0; }
                    .md-list.indent-2, .md-list.indent-3, .md-list.indent-4 { margin-left: 28px; }
                    .md-list.indent-5, .md-list.indent-6, .md-list.indent-7, .md-list.indent-8 { margin-left: 40px; }
                    .md-list.indent-9, .md-list.indent-10, .md-list.indent-11, .md-list.indent-12 { margin-left: 52px; }
                    .md-h1, .md-h2, .md-h3 { font-weight: bold; margin: 8px 0 4px 0; }
                    .md-h1 { font-size: 15px; }
                    .md-h2 { font-size: 14px; }
                    .md-h3 { font-size: 13px; }
                    .report-card { margin-top: 2px; padding: 8px 10px; border: 1px solid $borderColor; background: $codeBg; }
                    .report-field { margin: 0 0 6px 0; }
                    .report-label { font-weight: bold; color: $botNameColor; }
                    .report-value { color: $textColor; }
                    .meta { font-size: 10px; color: $metaColor; margin-top: 4px; }
                    .name { font-size: 11px; font-weight: bold; margin-bottom: 4px; }
                    .code { font-family: Monospaced; background:$codeBg; border:1px solid $borderColor; padding:2px 4px; }
                    pre { font-family: Monospaced; background:$codeBg; border:1px solid $borderColor; padding:8px; }
                    .md-table-wrap { margin: 8px 0 10px 0; overflow-x: auto; }
                    .md-table { border-collapse: collapse; width: 100%; background: $botBg; }
                    .md-table th, .md-table td { border: 1px solid $borderColor; padding: 6px 8px; text-align: left; vertical-align: top; }
                    .md-table th { background: $codeBg; font-weight: bold; }
                    a { color:$linkColor; text-decoration:none; }
                    .empty { border:1px solid $borderColor; padding: 12px; color: $metaColor; background: $botBg; }
                  </style>
                </head>
                <body><div class='wrap'>
                """.trimIndent()
            )
            if (messages.isEmpty()) {
                sb.append("<div class='empty'>Start with a licensing question or open Licensing Configuration to initialize project context.</div>")
            }
            for (m in messages) {
                val roleClass = if (m.role == "user") "user" else "bot"
                val time = tsFmt.format(Instant.ofEpochMilli(m.ts))
                val modelStr = m.model?.let { " — <span class='meta'>" + escapeHtml(it) + "</span>" } ?: ""
                val displayText = normalizeStructuredReportText(m.text)
                val renderedBody = markdownToHtmlBasic(displayText)
                val htmlText = if (looksLikeStructuredReport(displayText)) {
                    "<div class='report-card'>$renderedBody</div>"
                } else {
                    renderedBody
                }
                val name = if (m.role == "user") "You" else "LiA"
                val nameColor = if (m.role == "user") userNameColor else botNameColor
                val avatarLetter = if (m.role == "user") "U" else "L"
                val avatarBg = if (m.role == "user") userAvatarBg else botAvatarBg
                val rowHtml = if (m.role == "user") {
                    // User on the right: message cell first, avatar cell on the far right
                    """
                    <table width='100%' cellspacing='4' cellpadding='0'>
                      <tr>
                        <td width='*' align='right'>
                          <div class='msg $roleClass'>
                            <div class='name' style='color:$nameColor;'>$name</div>
                            $htmlText
                            <div class='meta'>$time$modelStr</div>
                          </div>
                        </td>
                        <td width='28' align='center' valign='top' bgcolor='$avatarBg'>
                          <font color='#ffffff'><b>$avatarLetter</b></font>
                        </td>
                      </tr>
                    </table>
                    """.trimIndent()
                } else {
                    // Bot on the left: avatar first, message next
                    """
                    <table width='100%' cellspacing='4' cellpadding='0'>
                      <tr>
                        <td width='28' align='center' valign='top' bgcolor='$avatarBg'>
                          <font color='#ffffff'><b>$avatarLetter</b></font>
                        </td>
                        <td width='*'>
                          <div class='msg $roleClass'>
                            <div class='name' style='color:$nameColor;'>$name</div>
                            $htmlText
                            <div class='meta'>$time$modelStr</div>
                          </div>
                        </td>
                      </tr>
                    </table>
                    """.trimIndent()
                }
                sb.append(rowHtml)
            }
            sb.append("</div></body></html>")
            return sb.toString()
        }

        private fun clearChatHistory() {
            invalidatePendingBackendRestore()
            project.getService(LicensingController::class.java).resetChatbotSession()
            messages.clear()
            persistMessages()
            refreshChatHtml()
            chatView?.caretPosition = 0
            UsageLogger.logInteraction(project, null, "chat_cleared", mapOf("messageCount" to 0))
            appendToChatHistory("Chat cleared. Ask a new licensing question when ready.")
        }

        private fun sendUserMessage(rawText: String) {
            val inputText = rawText.trim()
            if (inputText.isEmpty() || (inputPlaceholderVisible && inputText == inputPlaceholder)) return
            inputArea?.text = ""
            inputPlaceholderVisible = false
            inputArea?.foreground = inputTextColor ?: UIManager.getColor("TextArea.foreground")
            addMessage("user", inputText, selectedModelProp.get())
            submitMessage(inputText)
        }

        private fun sendSpecializedUserMessage(
            displayText: String,
            resourcePath: String,
            loadingMessages: List<String>
        ) {
            val specializedPrompt = loadResourceText(resourcePath) ?: run {
                appendToChatHistory("(error) Unable to load the specialized prompt for license assignment.")
                return
            }
            addMessage("user", displayText, selectedModelProp.get())
            submitMessageInternal(specializedPrompt, loadingMessages)
        }

        private fun addMessage(role: String, text: String, model: String? = null) {
            LOG.info("Adding message to UI $role: $text")
            val chatMessage = ChatMessage(role, text, model)
            messages += chatMessage
            currentSessionId?.let {
                ChatSessionStore.appendMessage(
                    project,
                    it,
                    ChatSessionStore.StoredMessage(chatMessage.role, chatMessage.text, chatMessage.model, chatMessage.ts),
                    selectedModelProp.get()
                )
            }
            UsageLogger.logInteraction(
                project,
                null,
                "chat_message",
                mapOf(
                    "role" to role,
                    "text" to text,
                    "model" to model,
                    "provider" to (model?.let { ModelSettings.getProviderForModel(it).name })
                )
            )
            refreshChatHtml()
        }

        fun appendToChatHistory(text: String) {
            addMessage("bot", text)
        }

        fun startSbomAnimation() {
            startLoadingAnimation(listOf("Analyzing dependencies", "Generating SBOM", "Querying model"))
        }

        fun startSubmitAnimation() {
            startLoadingAnimation(listOf("Collecting license context", "Querying model"))
        }

        fun startLoadingAnimation(messages: List<String>) {
            SwingUtilities.invokeLater {
                loadingDepth += 1
                loaderLabel?.isVisible = true
                //val messages = listOf("Analyzing dependencies", "Generating SBOM", "Querying model")
                var currentIndex = 0
                var dotTick = 0

                loaderLabel?.text = messages[currentIndex]

                loadingTimer?.stop()
                loadingTimer = Timer(500) {
                    dotTick = (dotTick + 1) % 4
                    val dots = ".".repeat(dotTick)

                    if (currentIndex < messages.size - 1) {
                        // Step through the first two messages once
                        loaderLabel?.text = "${messages[currentIndex]}$dots"

                        if (dotTick == 0) {
                            currentIndex++
                            loaderLabel?.text = messages[currentIndex]
                        }
                    } else {
                        // Stay on the last message and keep cycling dots forever
                        loaderLabel?.text = "${messages.last()}$dots"
                    }
                }

                loadingTimer?.start()
            }
        }


        fun stopAnimation() {
            SwingUtilities.invokeLater {
                if (loadingDepth > 0) {
                    loadingDepth -= 1
                }
                if (loadingDepth > 0) {
                    return@invokeLater
                }
                loadingTimer?.stop()
                loaderLabel?.isVisible = false
                loaderLabel?.text = "Loading"
            }
        }

        fun submitMessage(inputText: String) {
            submitMessageInternal(inputText, listOf("Collecting license context", "Querying model"))
        }

        fun submitAutomaticCheckMessage(
            inputText: String,
            onSuccess: Runnable? = null,
            onFailure: Runnable? = null
        ) {
            submitMessageInternal(
                inputText,
                listOf("Analyzing dependency update", "Checking license compatibility", "Querying model"),
                onSuccess,
                onFailure
            )
        }

        private fun submitMessageInternal(
            inputText: String,
            loadingMessages: List<String>,
            onSuccess: Runnable? = null,
            onFailure: Runnable? = null
        ) {
            LOG.info("Submit message to the LLM: $inputText")
            val selectedModel = selectedModelProp.get()
            val submitMode = if (loadingMessages.size > 2) "automatic" else "manual"
            UsageLogger.logInteraction(
                project,
                null,
                "model_request",
                mapOf(
                    "mode" to submitMode,
                    "input" to inputText,
                    "model" to selectedModel,
                    "provider" to ModelSettings.getProviderForModel(selectedModel).name
                )
            )
            startLoadingAnimation(loadingMessages)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    awaitPendingBackendRestore()
                    val chatbotSession = project.getService(LicensingController::class.java).getChatbotSession(selectedModel)
                    val response = chatbotSession.submitPrompt(inputText)
                    SwingUtilities.invokeLater {
                        addMessage("bot", response)
                        onSuccess?.run()
                    }
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    val isOpenAiModel = ModelSettings.getProviderForModel(selectedModel) == ModelProvider.OPENAI
                    val friendlyMessage = if (
                        message.contains("OpenAI API key not configured", ignoreCase = true) ||
                        message.contains("Please configure the tool by providing an OpenAI API Key.", ignoreCase = true)
                    ) {
                        "Please configure the tool by providing an OpenAI API Key."
                    } else if (
                        !isOpenAiModel && (
                            message.contains("Ollama host is required", ignoreCase = true) ||
                            message.contains("Please configure the tool by providing a valid Ollama host.", ignoreCase = true) ||
                            message.contains("Cannot reach Ollama host", ignoreCase = true) ||
                            message.contains("Ollama request timed out", ignoreCase = true) ||
                            message.contains("ensure Ollama is running", ignoreCase = true) ||
                            message.contains("Model request failed with HTTP", ignoreCase = true)
                        )
                    ) {
                        "Please configure the tool by providing a valid Ollama host, and ensure Ollama is running."
                    } else {
                        "(error) $message"
                    }
                    UsageLogger.logInteraction(
                        project,
                        null,
                        "model_request_failed",
                        mapOf(
                            "mode" to submitMode,
                            "input" to inputText,
                            "model" to selectedModel,
                            "provider" to ModelSettings.getProviderForModel(selectedModel).name,
                            "error" to message
                        )
                    )
                    SwingUtilities.invokeLater {
                        addMessage("bot", friendlyMessage)
                        onFailure?.run()
                    }
                    val expectedConfigOrNetwork =
                        message.contains("OpenAI API key not configured", ignoreCase = true) ||
                        message.contains("Please configure the tool by providing an OpenAI API Key.", ignoreCase = true) ||
                        message.contains("Ollama host is required", ignoreCase = true) ||
                        message.contains("Please configure the tool by providing a valid Ollama host.", ignoreCase = true) ||
                        message.contains("Cannot reach OpenAI endpoint", ignoreCase = true) ||
                        message.contains("Cannot reach Ollama host", ignoreCase = true) ||
                        message.contains("request timed out", ignoreCase = true) ||
                        message.contains("Model request failed with HTTP", ignoreCase = true)
                    if (expectedConfigOrNetwork) {
                        LOG.warn("Message submission blocked: $message")
                    } else {
                        LOG.error("Error during message submission: $message", t)
                    }
                } finally {
                    stopAnimation()
                }
            }
        }

        fun buildPanel(): JComponent {
            val root = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
            root.border = JBUI.Borders.empty(8)
            root.background = UIManager.getColor("Panel.background")

            val chrome = JBColor(Color(0xF3, 0xF5, 0xF8), Color(0x1E, 0x21, 0x27))
            val borderColor = JBColor(Color(0xD5, 0xDA, 0xE2), Color(0x3A, 0x40, 0x49))
            val accent = JBColor(Color(0x4A, 0x85, 0xFF), Color(0x4A, 0x85, 0xFF))

            val header = JBPanel<JBPanel<*>>(BorderLayout())
            header.background = chrome
            header.border = JBUI.Borders.customLineBottom(borderColor)

            val headerLeftTools = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply { isOpaque = false }
            val headerRightTools = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply { isOpaque = false }
            headerLeftTools.add(JButton("New Chat").apply {
                isFocusPainted = false
                addActionListener {
                    UsageLogger.logInteraction(project, null, "new_chat_button_clicked")
                    startNewSession()
                }
            })
            headerLeftTools.add(JButton(AllIcons.General.History).apply {
                isFocusPainted = false
                toolTipText = "Resume Session"
                preferredSize = JBUI.size(30, 30)
                minimumSize = JBUI.size(30, 30)
                maximumSize = JBUI.size(30, 30)
                margin = JBUI.emptyInsets()
                addActionListener {
                    UsageLogger.logInteraction(project, null, "resume_session_button_clicked")
                    showSessionPopup(this)
                }
            })
            headerRightTools.add(JButton("Licensing Configuration").apply {
                isFocusPainted = false
                addActionListener {
                    UsageLogger.logInteraction(project, null, "survey_button_clicked")
                    LicenseQuestionnaireDialog(project).show()
                }
            })
            headerRightTools.add(JButton("Help Assign License").apply {
                isFocusPainted = false
                toolTipText = "Ask LiA to recommend a project license based on the current configuration and dependencies"
                addActionListener {
                    UsageLogger.logInteraction(project, null, "help_assign_license_button_clicked")
                    if (getSurveyJson().isBlank()) {
                        appendToChatHistory(
                            "Complete the Licensing Configuration first so LiA can recommend a license based on project intent and dependency licenses."
                        )
                        LicenseQuestionnaireDialog(project).show()
                        return@addActionListener
                    }
                    sendSpecializedUserMessage(
                        "Help me assign a suitable license to this project.",
                        "prompts/help-assign-license.txt",
                        listOf("Collecting project context", "Analyzing dependency licenses", "Querying model")
                    )
                }
            })
            val connectionCaptionLabel = JBLabel("Connection").apply {
                toolTipText = "Current connectivity status for the selected model"
                border = JBUI.Borders.emptyRight(2)
            }
            connectionStatusLabel = JBLabel("\u25cf").apply {
                foreground = JBColor(Color(0x8A, 0x90, 0x99), Color(0x7F, 0x86, 0x90))
                toolTipText = "Checking model connectivity..."
                border = JBUI.Borders.emptyRight(2)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        refreshConnectionIndicator()
                    }
                })
            }
            headerRightTools.add(connectionCaptionLabel)
            headerRightTools.add(connectionStatusLabel)
            headerRightTools.add(JButton(AllIcons.General.GearPlain).apply {
                isFocusPainted = false
                toolTipText = "Settings"
                preferredSize = JBUI.size(30, 30)
                minimumSize = JBUI.size(30, 30)
                maximumSize = JBUI.size(30, 30)
                margin = JBUI.emptyInsets()
                addActionListener {
                    UsageLogger.logInteraction(project, null, "api_keys_button_clicked")
                    com.example.my_plugin.ApiKeysDialog(project).show()
                }
            })
            header.add(headerLeftTools, BorderLayout.WEST)
            header.add(headerRightTools, BorderLayout.EAST)
            root.add(header, BorderLayout.NORTH)

            chatView = JEditorPane("text/html", "").apply {
                isEditable = false
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                addHyperlinkListener { ev ->
                    if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                        java.awt.Desktop.getDesktop().browse(ev.url.toURI())
                    }
                }
                text = renderChatHtml()
            }

            val chatContainer = JBPanel<JBPanel<*>>(BorderLayout())
            chatContainer.border = JBUI.Borders.compound(JBUI.Borders.customLine(borderColor), JBUI.Borders.empty(4))
            chatContainer.add(JBScrollPane(chatView).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            root.add(chatContainer, BorderLayout.CENTER)

            loaderLabel = JBLabel("Loading...").apply { isVisible = false }
            val bottom = JBPanel<JBPanel<*>>(BorderLayout(0, 6))
            bottom.add(loaderLabel, BorderLayout.NORTH)

            val composer = JPanel(BorderLayout(0, 6))
            composer.background = chrome
            composer.border = JBUI.Borders.compound(JBUI.Borders.customLine(accent, 2), JBUI.Borders.empty(8))

            inputArea = JBTextArea().apply {
                lineWrap = true
                wrapStyleWord = true
                rows = 3
                border = JBUI.Borders.empty(6)
                inputTextColor = foreground
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) {
                            if (e.isShiftDown) {
                                this@apply.append("\n")
                            } else {
                                e.consume()
                                sendUserMessage(text)
                            }
                        }
                    }
                })
                addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent?) {
                        if (inputPlaceholderVisible) {
                            text = ""
                            foreground = inputTextColor ?: UIManager.getColor("TextArea.foreground")
                            inputPlaceholderVisible = false
                        }
                    }

                    override fun focusLost(e: FocusEvent?) {
                        if (text.trim().isEmpty()) {
                            text = inputPlaceholder
                            foreground = JBColor(Color(0x7D, 0x86, 0x93), Color(0x9AA1AA))
                            inputPlaceholderVisible = true
                        }
                    }
                })
                toolTipText = "Enter to submit, Shift+Enter for newline"
            }
            inputArea?.text = inputPlaceholder
            inputArea?.foreground = JBColor(Color(0x7D, 0x86, 0x93), Color(0x9AA1AA))
            inputPlaceholderVisible = true
            composer.add(JBScrollPane(inputArea).apply {
                preferredSize = Dimension(100, 92)
                border = JBUI.Borders.customLine(borderColor)
            }, BorderLayout.CENTER)

            val footer = JPanel(BorderLayout()).apply { isOpaque = false }
            val footerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
            val footerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
            modelSelectorLabel = JBLabel(selectedModelProp.get()).apply {
                border = JBUI.Borders.emptyLeft(10)
                foreground = UIManager.getColor("Button.foreground") ?: foreground
            }
            val modelSelectorIcon = JBLabel(AllIcons.General.ArrowDown).apply {
                border = JBUI.Borders.emptyRight(10)
                foreground = UIManager.getColor("Button.foreground") ?: foreground
            }
            val popupMouseListener = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    modelSelectorButton?.doClick()
                }
            }
            modelSelectorButton = JButton().apply {
                layout = BorderLayout()
                isFocusPainted = false
                preferredSize = Dimension(170, 34)
                minimumSize = preferredSize
                add(modelSelectorLabel, BorderLayout.CENTER)
                add(modelSelectorIcon, BorderLayout.EAST)
                addActionListener { showModelPopup(this) }
            }
            modelSelectorLabel?.addMouseListener(popupMouseListener)
            modelSelectorIcon.addMouseListener(popupMouseListener)
            footerLeft.add(modelSelectorButton)
            submitButton = JButton(SEND_ICON).apply {
                isFocusPainted = false
                toolTipText = "Send"
                preferredSize = JBUI.size(36, 34)
                minimumSize = preferredSize
                maximumSize = preferredSize
                margin = JBUI.emptyInsets()
                addActionListener { sendUserMessage(inputArea?.text ?: "") }
            }
            footerRight.add(submitButton)
            footer.add(footerLeft, BorderLayout.WEST)
            footer.add(footerRight, BorderLayout.EAST)
            composer.add(footer, BorderLayout.SOUTH)

            bottom.add(composer, BorderLayout.CENTER)
            root.add(bottom, BorderLayout.SOUTH)

            submitButton?.isEnabled = false
            refreshConnectionIndicator()
            return root
        }

        private fun showModelPopup(anchor: JComponent) {
            val entries = buildModelDropdownEntries(selectedModelProp.get())
            val step = object : BaseListPopupStep<ModelDropdownEntry>(null, entries) {
                override fun getTextFor(value: ModelDropdownEntry): String = value.text

                override fun getSeparatorAbove(value: ModelDropdownEntry): ListSeparator? {
                    if (!value.showSeparator) return null
                    return ListSeparator(value.providerLabel)
                }

                override fun isSelectable(value: ModelDropdownEntry): Boolean = value.modelId != null || value.isAction

                override fun onChosen(selectedValue: ModelDropdownEntry, finalChoice: Boolean): PopupStep<*>? {
                    if (selectedValue.isAction) {
                        val typed = promptForCustomModel().orEmpty()
                        if (typed.isNotBlank()) {
                            selectedModelProp.set(typed)
                        }
                    } else if (selectedValue.modelId != null) {
                        selectedModelProp.set(selectedValue.modelId)
                    }
                    return PopupStep.FINAL_CHOICE
                }
            }
            JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
        }

        private fun showSessionPopup(anchor: JComponent) {
            val sessions = ChatSessionStore.listSessions(project)
            if (sessions.isEmpty()) {
                Messages.showInfoMessage(project, "No previous sessions were found for this project.", "Resume Session")
                return
            }
            val entries = sessions.mapIndexed { index, session ->
                SessionListEntry(
                    text = "${sessionListFmt.format(Instant.ofEpochMilli(session.updatedAt))} — ${session.title()}",
                    sessionId = session.id,
                    showSeparator = index == 0,
                    separatorText = "Previous Sessions"
                )
            }
            val step = object : BaseListPopupStep<SessionListEntry>(null, entries) {
                override fun getTextFor(value: SessionListEntry): String = value.text

                override fun getSeparatorAbove(value: SessionListEntry): ListSeparator? {
                    if (!value.showSeparator) return null
                    return ListSeparator(value.separatorText)
                }

                override fun onChosen(selectedValue: SessionListEntry, finalChoice: Boolean): PopupStep<*>? {
                    val session = ChatSessionStore.loadSession(project, selectedValue.sessionId)
                    if (session != null) {
                        ChatSessionStore.setActiveSession(project, session.id)
                        restoreSession(session)
                    }
                    return PopupStep.FINAL_CHOICE
                }
            }
            JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
        }

        private fun promptForCustomModel(): String? {
            val typed = Messages.showInputDialog(
                project,
                "Enter the model id to use.",
                "Custom Model",
                null,
                selectedModelProp.get(),
                null
            )
            return typed?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun providerLabel(provider: ModelProvider): String =
            when (provider) {
                ModelProvider.OLLAMA -> "Ollama"
                ModelProvider.OPENAI -> "OpenAI"
            }

        private fun buildModelDropdownEntries(selectedModel: String): List<ModelDropdownEntry> {
            val entries = mutableListOf<ModelDropdownEntry>()
            val providers = listOf(ModelProvider.OLLAMA, ModelProvider.OPENAI)
            for (provider in providers) {
                val models = ModelSettings.availableModels(provider)
                if (models.isEmpty()) continue
                val providerLabel = providerLabel(provider)
                models.forEachIndexed { index, model ->
                    entries += ModelDropdownEntry(
                        text = model,
                        modelId = model,
                        providerLabel = providerLabel,
                        showSeparator = index == 0
                    )
                }
            }
            if (selectedModel.isNotBlank() && entries.none { it.modelId == selectedModel }) {
                entries += ModelDropdownEntry(
                    text = selectedModel,
                    modelId = selectedModel,
                    providerLabel = "Custom",
                    showSeparator = true
                )
            }
            entries += ModelDropdownEntry(
                text = "Custom model...",
                providerLabel = if (entries.any { it.providerLabel == "Custom" }) null else "Custom",
                showSeparator = entries.none { it.providerLabel == "Custom" },
                isAction = true
            )
            return entries
        }

        fun enableSubmitButton() { submitButton?.isEnabled = true }
        fun disableSubmitButton() { submitButton?.isEnabled = false }
        fun getMessageCount(): Int = messages.size

        fun disableInputArea() { inputArea?.isEnabled = false }
        fun enableInputArea() { inputArea?.isEnabled = true }

        // Function to check if the tool window is visible
        fun isToolWindowVisible(project: Project): Boolean {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("LiA")
            return toolWindow?.isVisible ?: false
        }

        fun toggleToolWindowVisibility(project: Project) {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("LiA")
            if (toolWindow != null) {
                if (toolWindow.isVisible) {
                    toolWindow.hide(null)
                } else {
                    toolWindow.show(null)
                }
            }
        }

        fun getSurveyJson(): String {
            // read the license-survey.json
            val surveyFile = java.io.File(project.basePath, ".license-tool/license-survey.json")
            if (!surveyFile.exists()) return ""
            return surveyFile.readText(Charsets.UTF_8)
        }

        private fun maybeRunProactiveLicenseTransitionCheck(origin: String) {
            val survey = loadSurveyRoot() ?: return
            val intended = survey.optString("intendedLicense", "").trim()
            val intendedMode = survey.optString("intendedLicenseMode", "SELECTED_LICENSE").trim()
            val detectedObj = survey.optJSONObject("detectedLicense")
            val detected = when {
                detectedObj != null -> detectedObj.optString("detectedType", "").trim()
                else -> survey.optJSONArray("existingLicensesUsed")?.optString(0, "")?.trim() ?: ""
            }

            if (intended.isBlank()) return

            val hasDetectedLicense = detected.isNotBlank()
            if (hasDetectedLicense && intended.equals(detected, ignoreCase = true)) return
            val startedAtMs = System.currentTimeMillis()
            UsageLogger.logInteraction(
                project,
                null,
                "proactive_compliance_check_started",
                mapOf(
                    "origin" to origin,
                    "trigger" to "survey",
                    "intendedLicense" to intended,
                    "intendedLicenseMode" to intendedMode,
                    "detectedLicense" to detected,
                    "hasDetectedLicense" to hasDetectedLicense
                )
            )

            val prompt = buildString {
                append("Automatic licensing guidance triggered by questionnaire update.")
                when (intendedMode) {
                    "PROPRIETARY_COMMERCIAL" -> {
                        append("\nSelected licensing mode: proprietary/commercial.")
                        append("\nDetected repository license: ").append(if (hasDetectedLicense) detected else "not found")
                        append("\nPlease explain what can be inferred from the current dependency set and ask the user the minimum clarifying questions needed about the intended proprietary/commercial terms, distribution model, and reuse constraints.")
                    }
                    "UNASSIGNED" -> {
                        append("\nSelected licensing mode: unassigned.")
                        append("\nDetected repository license: ").append(if (hasDetectedLicense) detected else "not found")
                        append("\nPlease help the user identify a suitable project license using the questionnaire answers and current dependency set. Suggest plausible options and key tradeoffs.")
                    }
                    else -> if (hasDetectedLicense) {
                        append("\nCurrent detected repository license: ").append(detected)
                        append("\nIntended repository license: ").append(intended)
                        append("\nPlease assess whether changing the project license from detected to intended remains compliant with the current dependency set.")
                    } else {
                        append("\nNo repository LICENSE file was detected in the project.")
                        append("\nSelected intended repository license: ").append(intended)
                        append("\nPlease assess whether the current dependency set appears compliant with adopting the selected intended license for the project.")
                    }
                }
                if (intendedMode == "SELECTED_LICENSE" && !hasDetectedLicense) {
                    append("\nSelected intended repository license: ").append(intended)
                }
                append("\nUse the license questionnaire and dependency context already attached.")
                if (intendedMode == "SELECTED_LICENSE") {
                    append("\nReturn the result using exactly this structure and these labels, in this order:")
                    append("\nstatus:")
                    append("\nprojectLicenseDetected:")
                    append("\nprojectLicenseIntended:")
                    append("\nknownCompatible:")
                    append("\npotentialIssues:")
                    append("\nunknownDependencies:")
                    append("\nnotes:")
                    append("\nFor status, use only one of: COMPLIANT, POTENTIAL_ISSUE, NEEDS_VERIFICATION, NEEDS_LEGAL_REVIEW.")
                    append("\nUse only the provided context. Do not invent licenses, dependencies, or conflicts.")
                    append("\nIf a dependency license is missing, place it under unknownDependencies.")
                    append("\nIf no potential issue is supported by the context, say so explicitly under potentialIssues.")
                    append("\nInclude in notes that the output is not legal advice and mention any important uncertainty.")
                } else {
                    append("\nReturn a concise user-facing answer.")
                    append("\nDo not pretend a final license has already been chosen.")
                    append("\nUse only the provided context and mention uncertainty explicitly.")
                }
                append("\nThis check was triggered by: ").append(origin)
            }
            val statusMessage = when (intendedMode) {
                "PROPRIETARY_COMMERCIAL" -> "Automatic guidance started: reviewing proprietary/commercial licensing needs."
                "UNASSIGNED" -> "Automatic guidance started: helping identify a suitable project license."
                else -> if (hasDetectedLicense) {
                    "Automatic check started: detected license differs from intended license."
                } else {
                    "Automatic check started: verifying dependencies against the selected intended license."
                }
            }
            appendToChatHistory(statusMessage)
            submitAutomaticCheckMessage(
                prompt,
                Runnable {
                    UsageLogger.logInteraction(
                        project,
                        null,
                        "proactive_compliance_check_completed",
                        mapOf(
                            "origin" to origin,
                            "trigger" to "survey",
                            "durationMs" to (System.currentTimeMillis() - startedAtMs)
                        )
                    )
                },
                Runnable {
                    UsageLogger.logInteraction(
                        project,
                        null,
                        "proactive_compliance_check_failed",
                        mapOf(
                            "origin" to origin,
                            "trigger" to "survey",
                            "durationMs" to (System.currentTimeMillis() - startedAtMs)
                        )
                    )
                }
            )
        }

        private fun loadSurveyRoot(): JSONObject? {
            return try {
                val raw = getSurveyJson()
                if (raw.isBlank()) null else JSONObject(raw)
            } catch (_: Throwable) {
                null
            }
        }

        private fun buildContextForPrompt(): String? {
            val parts = mutableListOf<String>()
            val survey = loadSurveyJson()
            if (!survey.isNullOrBlank()) {
                parts += "License questionnaire (JSON):\n$survey"
            }
            val deps = loadDependencySummary()
            if (!deps.isNullOrBlank()) {
                parts += "Dependencies (from SBOM):\n$deps"
            }
            if (parts.isEmpty()) return null
            return parts.joinToString("\n\n")
        }

        private fun loadSurveyJson(): String? {
            val base = project.basePath ?: return null
            val path = Path.of(base, ".license-tool", "license-survey.json")
            return try {
                if (!Files.exists(path)) return null
                val text = Files.readString(path, StandardCharsets.UTF_8)
                val root = JSONObject(text)
                // Strip large/raw detected-license payloads from LLM context.
                root.optJSONObject("detectedLicense")?.let { detected ->
                    detected.remove("excerpt")
                    detected.remove("fullText")
                    detected.remove("rawText")
                    detected.remove("licenseText")
                }
                val sanitized = root.toString()
                // keep context bounded
                if (sanitized.length > 8000) sanitized.take(8000) + "\n...(truncated)" else sanitized
            } catch (_: Throwable) {
                null
            }
        }

        private fun loadResourceText(resourcePath: String): String? {
            return try {
                val stream: InputStream = MyToolWindowFactory::class.java.classLoader
                    .getResourceAsStream(resourcePath) ?: return null
                stream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            } catch (_: Throwable) {
                null
            }
        }

        private fun loadDependencySummary(): String? {
            val base = project.basePath ?: return null
            val summaryPath = Path.of(base, ".license-tool", "dependency-summary.json")
            if (Files.exists(summaryPath)) {
                val fromSummary = readDependencySummary(summaryPath)
                if (!fromSummary.isNullOrBlank()) return fromSummary
            }

            val path = Path.of(base, ".license-tool", "bom.xml")
            if (!Files.exists(path)) return null
            return try {
                val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val doc = builder.parse(path.toFile())
                val components = SbomComponentExtractor.extractDependencyComponents(doc)
                if (components.isEmpty()) return null
                val maxItems = 200
                val lines = ArrayList<String>(minOf(components.size, maxItems))
                for (i in 0 until components.size) {
                    if (lines.size >= maxItems) break
                    val comp = components[i]
                    val group = comp.getElementsByTagName("group")?.item(0)?.textContent ?: ""
                    val name = comp.getElementsByTagName("name")?.item(0)?.textContent ?: ""
                    val version = comp.getElementsByTagName("version")?.item(0)?.textContent ?: ""
                    val id = listOf(group, name, version).filter { it.isNotBlank() }.joinToString(":")
                    val licenseInfo = extractLicenseInfo(comp)
                    if (id.isNotBlank()) {
                        val suffix = if (licenseInfo.isNullOrBlank()) "" else " - $licenseInfo"
                        lines.add(id + suffix)
                    }
                }
                val suffix = if (components.size > maxItems) "\n...(truncated, ${components.size - maxItems} more)" else ""
                lines.joinToString("\n") + suffix
            } catch (_: Throwable) {
                null
            }
        }

        private fun readDependencySummary(path: Path): String? {
            return try {
                val text = Files.readString(path, StandardCharsets.UTF_8)
                val root = JSONObject(text)
                val deps = root.optJSONArray("dependencies") ?: return null
                if (deps.length() == 0) return null
                val maxItems = 200
                val lines = ArrayList<String>(minOf(deps.length(), maxItems))
                for (i in 0 until deps.length()) {
                    if (lines.size >= maxItems) break
                    val dep = deps.optJSONObject(i) ?: continue
                    val group = dep.optString("group", "")
                    val name = dep.optString("name", "")
                    val version = dep.optString("version", "")
                    val id = listOf(group, name, version).filter { it.isNotBlank() }.joinToString(":")
                    if (id.isBlank()) continue
                    val licenses = dep.optJSONArray("licenses")
                    val licenseNames = mutableListOf<String>()
                    if (licenses != null) {
                        for (j in 0 until licenses.length()) {
                            val lic = licenses.optJSONObject(j) ?: continue
                            val licName = lic.optString("type", "")
                            if (licName.isNotBlank()) licenseNames.add(licName)
                        }
                    }
                    val relationship = when {
                        !dep.has("direct") -> ""
                        dep.optBoolean("direct", false) -> " (direct)"
                        dep.optString("introducedBy", "").isNotBlank() ->
                            " (transitive via ${dep.optString("introducedBy").trim()})"
                        else -> " (transitive)"
                    }
                    val suffix = if (licenseNames.isEmpty()) "" else " - " + licenseNames.joinToString(", ")
                    lines.add(id + relationship + suffix)
                }
                val suffix = if (deps.length() > maxItems) "\n...(truncated, ${deps.length() - maxItems} more)" else ""
                lines.joinToString("\n") + suffix
            } catch (_: Throwable) {
                null
            }
        }

        private fun extractLicenseInfo(comp: Element): String? {
            // CycloneDX: component/licenses/license/id or name
            val licensesNodes = comp.getElementsByTagName("licenses")
            if (licensesNodes.length == 0) return null
            val licensesEl = licensesNodes.item(0) as? Element ?: return null
            val licenseNodes = licensesEl.getElementsByTagName("license")
            if (licenseNodes.length == 0) return null
            val licenses = mutableListOf<String>()
            for (i in 0 until licenseNodes.length) {
                val licEl = licenseNodes.item(i) as? Element ?: continue
                val id = licEl.getElementsByTagName("id")?.item(0)?.textContent
                val name = licEl.getElementsByTagName("name")?.item(0)?.textContent
                val value = when {
                    !id.isNullOrBlank() -> id.trim()
                    !name.isNullOrBlank() -> name.trim()
                    else -> null
                }
                if (!value.isNullOrBlank()) licenses.add(value)
            }
            return if (licenses.isEmpty()) null else licenses.joinToString(", ")
        }

        // Register the Java-based listener and wire callbacks to UI updates
        fun registerSurveyListener() {
            // create a callback implementing the SurveyChangeListener Java interface
            val callback = object : SurveyChangeListener {
                override fun onSurveyCreated() {
                    ApplicationManager.getApplication().invokeLater {
                        enableSubmitButton()
                        enableInputArea()
                        appendToChatHistory("Licensing configuration saved — you can now submit queries.")
                        maybeRunProactiveLicenseTransitionCheck("survey_created")
                        LOG.info("Survey created event handled, UI updated.")
                    }
                }

                override fun onSurveyChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        appendToChatHistory("Licensing configuration updated.")
                        LOG.info("Survey updated event handled, UI updated.")
                        maybeRunProactiveLicenseTransitionCheck("survey_changed")
                    }
                }

                override fun onSurveyDeleted() {
                    ApplicationManager.getApplication().invokeLater {
                        disableSubmitButton()
                        disableInputArea()
                        appendToChatHistory("Licensing configuration removed. Please re-open Licensing Configuration to enable the chat.")
                        LOG.info("Survey deleted event handled, UI updated.")
                    }
                }
            }

            surveyListener = LicenseQuestionnaireListener(project, callback)
            surveyListener?.register()
        }

        override fun dispose() {
            invalidatePendingBackendRestore()
            try { loadingTimer?.stop() } catch (_: Throwable) {}
            loadingTimer = null
            // make sure to unregister listener if still registered
            try { surveyListener?.unregister() } catch (_: Throwable) {}
            surveyListener = null
            UsageLogger.setCurrentSessionId(project, null)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val ui = ChatUi(project)
        val panel = ui.buildPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        MyToolWindowBridge.getInstance(project).ui = ui

        val userId = UsageLogger.loadUserId(project)

        ui.initializeSession()

        UsageLogger.logEvent(project, userId, "tool_opened", mapOf("toolWindow" to "LiA"))
        UsageLogger.logInteraction(project, userId, "tool_window_opened", mapOf("toolWindow" to "LiA"))

        // Post-init greeting and gating submit button by survey presence
        val surveyJson = java.io.File(project.basePath, ".license-tool/license-survey.json")
        if (!surveyJson.exists()) {
            if (ui.getMessageCount() <= 1) {
                ui.appendToChatHistory("Before we start, please complete the licensing configuration.\nClick the 'Licensing Configuration' button above to open it.")
            }
            ui.disableSubmitButton()
            ui.disableInputArea()
        } else {
            ui.enableSubmitButton()
            ui.enableInputArea()
        }

        // Register the listener so UI reacts to create/change/delete events
        ui.registerSurveyListener()

        // Dispose UI when project is disposed to ensure a clean env on project switch
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable, ui)
        // Initial greeting
    }

    /*companion object {
        private val instances = mutableMapOf<Project, ChatUi>()
        fun getInstance(project: Project): ChatUi? = instances[project]
    }*/

}
