package com.example.my_plugin.license

import com.example.my_plugin.UsageLogger
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.*
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.FormBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import java.awt.Color
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

class LicenseQuestionnaireDialog(private val project: Project) : DialogWrapper(project, true) {
    private enum class SaveOutcome {
        SAVED,
        UNCHANGED,
        FAILED
    }

    private companion object {
        private const val INTENDED_MODE_SELECTED = "SELECTED_LICENSE"
        private const val INTENDED_MODE_PROPRIETARY = "PROPRIETARY_COMMERCIAL"
        private const val INTENDED_MODE_UNASSIGNED = "UNASSIGNED"
        private const val PROPRIETARY_LICENSE_LABEL = "Proprietary/commercial license"
        private const val UNASSIGNED_LICENSE_LABEL = "I don't know/Unassigned"
    }

    /*
    private val spdxLicenses = arrayOf(
        "Unlicense", "Apache-2.0", "MIT", "GPL-3.0-or-later", "GPL-3.0-only", "GPL-2.0-or-later", "GPL-2.0-only",
        "GPL-1.0-or-later", "GPL-1.0-only","BSD-3-Clause", "BSD-2-Clause", "LGPL-3.0-or-later", "MPL-2.0", "EPL-2.0"
    )
    */

    private val licenseChoices = arrayOf(
        "--Choose a License--",
        "Apache-2.0",
        "MIT",
        "GPL-3.0-or-later",
        "GPL-3.0-only",
        "GPL-2.0-or-later",
        "GPL-2.0-only",
        "GPL-1.0-or-later",
        "GPL-1.0-only",
        "BSD-3-Clause",
        "BSD-2-Clause",
        "LGPL-3.0-or-later",
        "MPL-2.0",
        "EPL-1.0",
        "EPL-2.0"
    )
    // --- UI fields ---
    // project
    private val tfProjectName = JBTextField(project.name)
    private val taProjectDescription = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }

    // author
    // private val tfAuthorName = JBTextField()
    // private val tfAuthorEmail = JBTextField()
    // private val tfAuthorOrg = JBTextField()

    // intent
    private val cbCommercialUse = JBCheckBox("Commercial use")
    private val cbDistribution = JBCheckBox("Allow re-distribution")
    private val cbModificationAllowed = JBCheckBox("Allow modification")
    private val cbPatentGrantRequired = JBCheckBox("Include patent grant")
    private val cbUseWithClosedSource = JBCheckBox("Allow closed‑source re-distribution")

    // constraints
    private val cbCopyleftRequired = JBCheckBox("Copyleft required")
    private val cbMustDiscloseSource = JBCheckBox("Must disclose source code")
    private val cbMustDocumentChanges = JBCheckBox("Must document changes")
    private val cbIncludeLicenseInBinary = JBCheckBox("Include license in binaries")

    // lists (use IntelliJ ComboBox)
    private val cbExistingLicenses = ComboBox(licenseChoices)
    private val rbSelectLicense = JBRadioButton("Select a license:", true)
    private val rbProprietaryLicense = JBRadioButton(PROPRIETARY_LICENSE_LABEL)
    private val rbUnassignedLicense = JBRadioButton(UNASSIGNED_LICENSE_LABEL)
    private val lblDetectedLicense = JBLabel("Detected license: (not found)")

    // notes
    private val taNotes = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }

    init {
        title = "Licensing Configuration"
        ButtonGroup().apply {
            add(rbSelectLicense)
            add(rbProprietaryLicense)
            add(rbUnassignedLicense)
        }
        val updateSelection = {
            cbExistingLicenses.isEnabled = rbSelectLicense.isSelected
        }
        rbSelectLicense.addActionListener { updateSelection() }
        rbProprietaryLicense.addActionListener { updateSelection() }
        rbUnassignedLicense.addActionListener { updateSelection() }
        init()
        updateSelection()
        UsageLogger.logInteraction(project, null, "survey_dialog_opened")
        loadFromJsonIfPresent() // ← load values if file exists
        // Ensure first-open dialog already shows detected license from project files.
        refreshDetectedLicenseFromProject(prefillIntendedIfUnset = !Files.exists(surveyFile()))
        // Reread from disk when dialog is shown (case: JSON modified in editor)
        SwingUtilities.invokeLater {
            this.window?.addWindowListener(object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent) {
                    loadFromJsonIfPresent()
                    refreshDetectedLicenseFromProject(prefillIntendedIfUnset = !Files.exists(surveyFile()))
                }
            })
        }
        cbUseWithClosedSource.addChangeListener {
            if (cbUseWithClosedSource.isSelected) {
                cbMustDiscloseSource.isSelected = false
            }
        }
        cbMustDiscloseSource.addChangeListener {
            if (cbMustDiscloseSource.isSelected) {
                cbUseWithClosedSource.isSelected = false
            }
        }
        listOf(
            cbCommercialUse,
            cbDistribution,
            cbModificationAllowed,
            cbPatentGrantRequired,
            cbUseWithClosedSource,
            cbCopyleftRequired,
            cbMustDiscloseSource,
            cbMustDocumentChanges,
            cbIncludeLicenseInBinary,
            rbSelectLicense,
            rbProprietaryLicense,
            rbUnassignedLicense
        ).forEach { it.alignmentX = JComponent.LEFT_ALIGNMENT }
        cbExistingLicenses.alignmentX = JComponent.LEFT_ALIGNMENT
        lblDetectedLicense.alignmentX = JComponent.LEFT_ALIGNMENT
    }

    override fun createCenterPanel(): JComponent {
        val projectPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", tfProjectName)
            .addLabeledComponent("Description:", JBScrollPane(taProjectDescription))
            .panel

        val intentPanel = FormBuilder.createFormBuilder()
            .addComponent(cbCommercialUse)
            .addComponent(cbDistribution)
            .addComponent(cbModificationAllowed)
            .addComponent(cbPatentGrantRequired)
            .addComponent(cbUseWithClosedSource)
            .panel

        val constraintsPanel = FormBuilder.createFormBuilder()
            .addComponent(cbCopyleftRequired)
            .addComponent(cbMustDiscloseSource)
            .addComponent(cbMustDocumentChanges)
            .addComponent(cbIncludeLicenseInBinary)
            .panel


        val repositoryLicenseLabel = JBLabel("Intended license:").apply {
            toolTipText = "The license you intend to apply to this repository."
            icon = AllIcons.General.ContextHelp
        }

        //val preferredLicenseLabel = JBLabel("Preferred license:").apply {
        //    toolTipText = "A license you would like to make your repository available under, but which is not currently assigned to your repository."
        //    icon = AllIcons.General.ContextHelp
        //}

        val detectedLabel = JBLabel("Detected license:").apply {
            toolTipText = "License detected from the LICENSE/COPYING file in the project root."
            icon = AllIcons.General.ContextHelp
        }

        val selectLicenseRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(rbSelectLicense)
            add(Box.createHorizontalStrut(8))
            add(cbExistingLicenses)
            add(Box.createHorizontalGlue())
        }
        val proprietaryRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(rbProprietaryLicense)
            add(Box.createHorizontalGlue())
        }
        val unassignedRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(rbUnassignedLicense)
            add(Box.createHorizontalGlue())
        }

        val intendedLicensePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(selectLicenseRow)
            add(Box.createVerticalStrut(6))
            add(proprietaryRow)
            add(Box.createVerticalStrut(6))
            add(unassignedRow)
        }

        val listsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(repositoryLicenseLabel, intendedLicensePanel)
            .addLabeledComponent(detectedLabel, lblDetectedLicense)
            .panel

        val notesPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Notes:", JBScrollPane(taNotes))
            .panel

        // helper to create bold section labels
        fun sectionLabel(title: String): JComponent = JBLabel("<html><b>$title</b></html>").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        // helper to create a bordered section that contains the label and content
        fun sectionPanel(title: String, content: JComponent): JComponent {
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0xDDDDDD)),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)
                )
                add(sectionLabel(title))
                add(Box.createVerticalStrut(6))
                add(content)
            }
        }

        // Single page: stack the sections vertically and make the whole thing scrollable
        val main = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sectionPanel("Project", wrap(projectPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Intent", wrap(intentPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Constraints", wrap(constraintsPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Licenses", wrap(listsPanel)))
            add(Box.createVerticalStrut(8))

            add(sectionPanel("Notes", wrap(notesPanel)))
        }

        return JBScrollPane(main)
    }

    private fun wrap(c: JComponent): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(c)
    }

    // =========================
    // JSON I/O
    // =========================

    private fun projectDir(): Path =
        project.basePath?.let { Path.of(it) } ?: Path.of(".")

    private fun surveyDir(): Path = projectDir().resolve(".license-tool")

    private fun surveyFile(): Path = surveyDir().resolve("license-survey.json")

    private fun ensureSurveyDir() {
        if (!Files.exists(surveyDir())) {
            Files.createDirectories(surveyDir())
        }
    }

    private fun loadFromJsonIfPresent() {
        val file = surveyFile()
        if (!Files.exists(file)) return
        try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val root = JSONObject(text)

            // 1. project
            root.optJSONObject("project")?.let { pj ->
                tfProjectName.text = pj.optString("name", "")
                taProjectDescription.text = pj.optString("description", "")
                // tfRepository.text = pj.optString("repository", "")
            }

            // 2. author
           /* root.optJSONObject("author")?.let { au ->
                tfAuthorName.text = au.optString("name", "")
                tfAuthorEmail.text = au.optString("email", "")
                tfAuthorOrg.text = au.optString("organization", "")
            }*/

            // 3. intent
            root.optJSONObject("intent")?.let { itj ->
                cbCommercialUse.isSelected = itj.optBoolean("commercialUse", false)
                cbDistribution.isSelected = itj.optBoolean("distribution", false)
                cbModificationAllowed.isSelected = itj.optBoolean("modificationAllowed", false)
                cbPatentGrantRequired.isSelected = itj.optBoolean("patentGrantRequired", false)
                cbUseWithClosedSource.isSelected = itj.optBoolean("useWithClosedSource", false)
            }

            // 4. constraints
            root.optJSONObject("constraints")?.let { cj ->
                cbCopyleftRequired.isSelected = cj.optBoolean("copyleftRequired", false)
                cbMustDiscloseSource.isSelected = cj.optBoolean("mustDiscloseSource", false)
                cbMustDocumentChanges.isSelected = cj.optBoolean("mustDocumentChanges", false)
                cbIncludeLicenseInBinary.isSelected = cj.optBoolean("includeLicenseInBinary", false)
            }

            // lists (prefer intendedLicense; fall back to existingLicensesUsed for backward compatibility)
            val intended = root.optString("intendedLicense", "")
            val intendedMode = normalizeIntendedMode(root.optString("intendedLicenseMode", ""), intended)
            val existing = root.optJSONArray("existingLicensesUsed")?.optString(0, "") ?: ""
            val loadedLicense = normalizeLicenseForChoice(if (intended.isNotBlank()) intended else existing)
            cbExistingLicenses.selectedItem = loadedLicense ?: licenseChoices.first()
            when (intendedMode) {
                INTENDED_MODE_PROPRIETARY -> rbProprietaryLicense.isSelected = true
                INTENDED_MODE_UNASSIGNED -> rbUnassignedLicense.isSelected = true
                else -> rbSelectLicense.isSelected = true
            }
            cbExistingLicenses.isEnabled = rbSelectLicense.isSelected
            val detectedType = root.optJSONObject("detectedLicense")?.optString("detectedType", "") ?: ""
            lblDetectedLicense.text = if (detectedType.isNotBlank()) detectedType else "Not found"
            //cbPreferredLicenses.selectedItem = root.optJSONArray("preferredLicenses")?.optString(0, "") ?: ""

            // 9. notes
            taNotes.text = root.optString("notes", "")
            UsageLogger.logInteraction(project, null, "survey_loaded", mapOf("path" to file.toString()))
        } catch (t: Throwable) {
            t.printStackTrace()
            UsageLogger.logInteraction(project, null, "survey_load_failed", mapOf("error" to (t.message ?: t.javaClass.simpleName)))
            // Se fallisce il parsing, lascio i default nei campi
        }
    }

    private fun refreshDetectedLicenseFromProject(prefillIntendedIfUnset: Boolean) {
        val detected = detectLicenseInfo()
        val detectedType = detected?.optString("detectedType", "")?.trim().orEmpty()
        lblDetectedLicense.text = if (detectedType.isNotBlank()) detectedType else "Not found"
        UsageLogger.logInteraction(
            project,
            null,
            "survey_license_detected",
            mapOf(
                "detectedType" to if (detectedType.isBlank()) "Not found" else detectedType,
                "prefillIntendedIfUnset" to prefillIntendedIfUnset
            )
        )

        if (!prefillIntendedIfUnset) return
        if (!rbSelectLicense.isSelected) return
        val selected = cbExistingLicenses.selectedItem?.toString()?.trim().orEmpty()
        if (selected.isNotBlank() && selected != licenseChoices.first()) return
        val normalizedDetected = normalizeLicenseForChoice(detectedType)
        if (normalizedDetected != null && !normalizedDetected.equals("UNKNOWN", ignoreCase = true)) {
            cbExistingLicenses.selectedItem = normalizedDetected
        }
    }

    private fun saveToJson(detected: JSONObject?, intendedLicense: String, intendedMode: String): SaveOutcome {
        try {
            ensureSurveyDir()
            val root = JSONObject()

            // 1. project
            val projectObj = JSONObject()
                .put("name", tfProjectName.text.trim())
                .put("description", taProjectDescription.text.trim())
                // .put("repository", tfRepository.text.trim())
            root.put("project", projectObj)

            // detected project license (from LICENSE/COPYING file if present)
            if (detected != null) {
                root.put("detectedLicense", detected)
            }

            // 2. author
            /*val authorObj = JSONObject()
                .put("name", tfAuthorName.text.trim())
                .put("email", tfAuthorEmail.text.trim())
                .put("organization", tfAuthorOrg.text.trim())
            root.put("author", authorObj)*/

            // 3. intent
            val intentObj = JSONObject()
                .put("commercialUse", cbCommercialUse.isSelected)
                .put("distribution", cbDistribution.isSelected)
                .put("modificationAllowed", cbModificationAllowed.isSelected)
                .put("patentGrantRequired", cbPatentGrantRequired.isSelected)
                .put("useWithClosedSource", cbUseWithClosedSource.isSelected)
            root.put("intent", intentObj)

            // 4. constraints
            val constraintsObj = JSONObject()
                .put("copyleftRequired", cbCopyleftRequired.isSelected)
                .put("mustDiscloseSource", cbMustDiscloseSource.isSelected)
                .put("mustDocumentChanges", cbMustDocumentChanges.isSelected)
                .put("includeLicenseInBinary", cbIncludeLicenseInBinary.isSelected)
            root.put("constraints", constraintsObj)

            // lists
            // existingLicensesUsed represents the actual detected license from LICENSE file
            val actual = detected?.optString("detectedType", "") ?: ""
            val existingLicenses = JSONArray()
            if (actual.isNotBlank()) {
                existingLicenses.put(actual)
            }
            root.put("existingLicensesUsed", existingLicenses)
            // intendedLicense represents the effective intended license (selected or inferred from detected)
            root.put("intendedLicense", intendedLicense)
            root.put("intendedLicenseMode", intendedMode)
            //root.put("preferredLicenses", JSONArray().put(cbPreferredLicenses.selectedItem ?: ""))

            // 9. notes
            root.put("notes", taNotes.text.trim())

            // write (pretty)
            val jsonText = root.toString(2)
            val existingRoot = loadExistingSurveyRoot()
            if (existingRoot != null && existingRoot.similar(root)) {
                UsageLogger.logInteraction(
                    project,
                    null,
                    "survey_save_skipped",
                    mapOf(
                        "reason" to "unchanged",
                        "path" to surveyFile().toString(),
                        "intendedLicense" to intendedLicense,
                        "intendedLicenseMode" to intendedMode
                    )
                )
                return SaveOutcome.UNCHANGED
            }
            Files.writeString(surveyFile(), jsonText + System.lineSeparator(), StandardCharsets.UTF_8)

            // Refresh IntelliJ VFS so listeners detect the new/updated file immediately
            try {
                val ioFile = surveyFile().toFile()
                val localFs = LocalFileSystem.getInstance()
                var vf = localFs.refreshAndFindFileByIoFile(ioFile)
                if (vf == null) vf = localFs.refreshAndFindFileByIoFile(ioFile.parentFile)
                if (vf != null) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                } else {
                    // Fallback to async full refresh if the specific file wasn't found
                    VirtualFileManager.getInstance().asyncRefresh(null)
                }
            } catch (vfEx: Throwable) {
                // Don't fail saving if refresh fails; just print for diagnostics
                vfEx.printStackTrace()
            }
            UsageLogger.logInteraction(
                project,
                null,
                "survey_saved",
                mapOf(
                    "path" to surveyFile().toString(),
                    "intendedLicense" to intendedLicense,
                    "intendedLicenseMode" to intendedMode,
                    "detectedLicense" to detected?.optString("detectedType", "UNKNOWN")
                )
            )
            return SaveOutcome.SAVED
        } catch (t: Throwable) {
            t.printStackTrace()
            UsageLogger.logInteraction(project, null, "survey_save_failed", mapOf("error" to (t.message ?: t.javaClass.simpleName)))
            return SaveOutcome.FAILED
        }
    }

    private fun loadExistingSurveyRoot(): JSONObject? {
        val file = surveyFile()
        if (!Files.exists(file)) return null
        return try {
            JSONObject(Files.readString(file, StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectLicenseInfo(): JSONObject? {
        val licenseFile = findLicenseFile() ?: return null
        val preview = readFilePreview(licenseFile, 2000)
        val detectedType = detectLicenseType(preview)
        val relPath = projectDir().relativize(licenseFile).toString()
        return JSONObject()
            .put("filePath", relPath)
            .put("detectedType", detectedType ?: "UNKNOWN")
            .put("source", "license_file")
    }

    private fun findLicenseFile(): Path? {
        val dir = projectDir()
        if (!Files.isDirectory(dir)) return null
        val candidates = listOf(
            "LICENSE", "LICENSE.txt", "LICENSE.md",
            "COPYING", "COPYING.txt", "COPYING.md",
            "NOTICE", "NOTICE.txt", "NOTICE.md"
        )

        for (name in candidates) {
            val path = dir.resolve(name)
            if (Files.exists(path)) return path
        }

        // case-insensitive match in project root
        val lowered = candidates.map { it.lowercase() }.toSet()
        Files.newDirectoryStream(dir).use { stream ->
            for (path in stream) {
                val fileName = path.fileName?.toString() ?: continue
                if (fileName.lowercase() in lowered) return path
            }
        }
        return null
    }

    private fun readFilePreview(path: Path, maxChars: Int): String {
        return try {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(1024)
                var read = reader.read(buf)
                while (read > 0 && sb.length < maxChars) {
                    sb.append(buf, 0, read)
                    read = reader.read(buf)
                }
                sb.toString()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun detectLicenseType(text: String): String? {
        if (text.isBlank()) return null
        val spdxRegex = Regex(
            "\\b(Apache-2\\.0|MIT|GPL-3\\.0(?:-only|-or-later)?|GPL-2\\.0(?:-only|-or-later)?|MPL-2\\.0|EPL-2\\.0|BSD-3-Clause|BSD-2-Clause|LGPL-3\\.0(?:-only|-or-later)?)\\b",
            RegexOption.IGNORE_CASE
        )
        spdxRegex.find(text)?.let { return it.value.uppercase() }

        val lower = text.lowercase()
        return when {
            lower.contains("apache license") && lower.contains("version 2") -> "Apache-2.0"
            lower.contains("mit license") -> "MIT"
            lower.contains("gnu general public license") && lower.contains("version 3") -> "GPL-3.0"
            lower.contains("gnu general public license") && lower.contains("version 2") -> "GPL-2.0"
            lower.contains("mozilla public license") && lower.contains("2.0") -> "MPL-2.0"
            lower.contains("eclipse public license") && lower.contains("2.0") -> "EPL-2.0"
            lower.contains("bsd 3-clause") -> "BSD-3-Clause"
            lower.contains("bsd 2-clause") -> "BSD-2-Clause"
            lower.contains("lesser general public license") && lower.contains("version 3") -> "LGPL-3.0"
            else -> null
        }
    }

    private fun normalizeLicenseForChoice(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw == licenseChoices.first()) return null
        return licenseChoices.firstOrNull { it.equals(raw, ignoreCase = true) && it != licenseChoices.first() }
            ?: raw
    }

    private fun normalizeIntendedMode(mode: String?, intendedLicense: String?): String {
        val rawMode = mode?.trim().orEmpty()
        if (rawMode == INTENDED_MODE_SELECTED || rawMode == INTENDED_MODE_PROPRIETARY || rawMode == INTENDED_MODE_UNASSIGNED) {
            return rawMode
        }
        return when (intendedLicense?.trim()) {
            PROPRIETARY_LICENSE_LABEL -> INTENDED_MODE_PROPRIETARY
            UNASSIGNED_LICENSE_LABEL -> INTENDED_MODE_UNASSIGNED
            else -> INTENDED_MODE_SELECTED
        }
    }

    // =========================
    // Dialog lifecycle
    // =========================

    override fun doOKAction() {
        if (tfProjectName.text.trim().isEmpty()) {
            UsageLogger.logInteraction(project, null, "survey_validation_failed", mapOf("reason" to "missing_project_name"))
            setErrorText("Project name is required.")
            return
        }

        val detected = detectLicenseInfo()
        val detectedType = detected?.optString("detectedType", "")?.trim().orEmpty()
        val normalizedDetected = normalizeLicenseForChoice(detectedType)
        val selectedIntended = cbExistingLicenses.selectedItem?.toString()?.trim().orEmpty()

        val (effectiveIntended, intendedMode) = when {
            rbProprietaryLicense.isSelected -> PROPRIETARY_LICENSE_LABEL to INTENDED_MODE_PROPRIETARY
            rbUnassignedLicense.isSelected -> UNASSIGNED_LICENSE_LABEL to INTENDED_MODE_UNASSIGNED
            else -> {
                val selectedLicense = if (selectedIntended.isBlank() || selectedIntended == licenseChoices.first()) {
                    if (normalizedDetected != null && !normalizedDetected.equals("UNKNOWN", ignoreCase = true)) {
                        normalizedDetected
                    } else {
                        UsageLogger.logInteraction(project, null, "survey_validation_failed", mapOf("reason" to "missing_intended_license"))
                        setErrorText("Select a license, choose Proprietary/commercial license, or choose I don't know/Unassigned.")
                        return
                    }
                } else {
                    normalizeLicenseForChoice(selectedIntended) ?: selectedIntended
                }
                selectedLicense to INTENDED_MODE_SELECTED
            }
        }

        when (saveToJson(detected, effectiveIntended, intendedMode)) {
            SaveOutcome.SAVED, SaveOutcome.UNCHANGED -> super.doOKAction()
            SaveOutcome.FAILED -> {
                setErrorText("Failed to save the licensing configuration. Check the IDE log for details.")
                return
            }
        }
    }

    override fun doCancelAction() {
        UsageLogger.logInteraction(project, null, "survey_dialog_cancelled")
        super.doCancelAction()
    }
}
