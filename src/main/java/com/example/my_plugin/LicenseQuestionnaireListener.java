// java
package com.example.my_plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LicenseQuestionnaireListener {

    private static final String SURVEY_RELATIVE_PATH = ".license-tool/license-survey.json";

    private final Project project;
    private final SurveyChangeListener callback;
    private volatile boolean registered = false;
    private MessageBusConnection connection;

    public LicenseQuestionnaireListener(Project project, SurveyChangeListener callback) {
        this.project = project;
        this.callback = callback;
    }

    public void register() {
        if (registered) return;

        connection = project.getMessageBus().connect(project);
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    String path = event.getPath();
                    if (!path.endsWith(SURVEY_RELATIVE_PATH)) continue;

                    if (event instanceof VFileCreateEvent) {
                        ApplicationManager.getApplication().invokeLater(callback::onSurveyCreated);
                    } else if (event instanceof VFileContentChangeEvent) {
                        ApplicationManager.getApplication().invokeLater(callback::onSurveyChanged);
                    } else if (event instanceof VFileDeleteEvent) {
                        ApplicationManager.getApplication().invokeLater(callback::onSurveyDeleted);
                    }
                }
            }
        });

        registered = true;
    }

    public void unregister() {
        if (!registered) return;
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
        registered = false;
    }
}
