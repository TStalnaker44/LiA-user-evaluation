package com.example.my_plugin

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project

object ApiKeyStore {
    private const val SERVICE_NAME = "Licensing Tool"
    private const val OPENAI_KEY = "openai"

    private fun globalAttributes(): CredentialAttributes {
        return CredentialAttributes("$SERVICE_NAME:$OPENAI_KEY")
    }

    private fun legacyProjectAttributes(project: Project): CredentialAttributes {
        return CredentialAttributes("$SERVICE_NAME:$OPENAI_KEY", project.locationHash)
    }

    fun getOpenAiKey(project: Project): String? {
        val passwordSafe = PasswordSafe.instance
        val globalKey = passwordSafe.getPassword(globalAttributes())
        if (!globalKey.isNullOrBlank()) {
            return globalKey
        }

        // Migrate old project-scoped keys to the new global slot on first read.
        val legacyKey = passwordSafe.getPassword(legacyProjectAttributes(project))
        if (!legacyKey.isNullOrBlank()) {
            passwordSafe.setPassword(globalAttributes(), legacyKey)
            return legacyKey
        }
        return null
    }

    fun setOpenAiKey(project: Project, key: String?) {
        val passwordSafe = PasswordSafe.instance
        passwordSafe.setPassword(globalAttributes(), key)
        passwordSafe.setPassword(legacyProjectAttributes(project), key)
    }
}
