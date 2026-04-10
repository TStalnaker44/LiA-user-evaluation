package com.example.my_plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class MyToolWindowBridge(private val project: Project) {
    var ui: MyToolWindowFactory.ChatUi? = null

    companion object {
        fun getInstance(project: Project) = project.getService(MyToolWindowBridge::class.java)
    }
}