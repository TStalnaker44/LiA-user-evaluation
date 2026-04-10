package com.example.my_plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import controller.LicensingController;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class PomChangeProjectListener extends PsiTreeChangeAdapter implements BulkFileListener {
    private final Project project;
    private final LicensingController licensingController;
    private final MergingUpdateQueue queue;

    public PomChangeProjectListener(Project project) {
        this.project = project;
        this.licensingController = project.getService(LicensingController.class);
        this.queue = new MergingUpdateQueue("license-tool-pom-change", 750, true, null, project);
    }

    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        boolean pomTouched = false;
        String changedPath = null;
        for (VFileEvent event : events) {
            if (event.getFile() == null) continue;
            String path = event.getFile().getPath();
            if (path.endsWith("pom.xml") && path.startsWith(basePath)) {
                pomTouched = true;
                changedPath = path;
                break;
            }
        }

        if (!pomTouched) return;

        final String pathForLog = changedPath;
        queue.queue(new Update("pom-change") {
            @Override
            public void run() {
                DumbService.getInstance(project).runWhenSmart(() -> {
                    if (pathForLog != null) {
                        LogInitializer.logState(PomChangeProjectListener.class, project, "pom_updated", java.util.Map.of("path", pathForLog));
                    }
                    LogInitializer.getLogger(PomChangeProjectListener.class)
                            .info("pom.xml updated; starting dependency pipeline for project " + project.getName());
                    licensingController.onDependencyChange(project, pathForLog);
                });
            }
        });
    }
}
