package com.example.my_plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class UserIdDialog(private val project: Project) : DialogWrapper(project, true) {
    private val tfUserId = JBTextField()

    init {
        title = "Enter User Identifier"
        isModal = true
        init()
        tfUserId.emptyText.text = "e.g. company-123 or email"
        tfUserId.border = JBUI.Borders.empty(6)
        // prefill with existing id if any
        UsageLogger.loadUserId(project)?.let { tfUserId.text = it }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.add(JLabel("User identifier:"), BorderLayout.WEST)
        panel.add(tfUserId, BorderLayout.CENTER)
        val note = JLabel("This id will be stored locally in .license-tool/ and used for usage logs.")
        note.font = note.font.deriveFont(note.font.size - 1f)
        panel.add(note, BorderLayout.SOUTH)
        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    override fun doOKAction() {
        val id = tfUserId.text.trim()
        if (id.isNotEmpty()) {
            UsageLogger.saveUserId(project, id)
            super.doOKAction()
        } else {
            setErrorText("User identifier can't be empty")
        }
    }

    // Only expose the OK action — remove Cancel button
    override fun createActions(): Array<Action> = arrayOf(okAction)

    // Prevent the dialog from being closed via ESC or the close icon
    override fun doCancelAction() {
        // keep the dialog open and show error
        setErrorText("You must enter a user identifier to continue.")
    }
}