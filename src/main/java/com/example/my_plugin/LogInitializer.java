package com.example.my_plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LogInitializer {

    // Keep track of which project paths we already initialized logging for.
    private static final Set<String> INITIALIZED = ConcurrentHashMap.newKeySet();

    public static void setupLoggingForProject(com.intellij.openapi.project.Project project) {
        // Use project base path as unique key; fall back to project name if basePath is null
        String projectKey = project.getBasePath() != null ? project.getBasePath() : project.getName();
        if (projectKey == null) {
            return;
        }

        if (!INITIALIZED.add(projectKey)) {
            return; // already initialized
        }

        // Ensure .license-tool exists for any project-local artifacts we write.
        String basePath = project.getBasePath();
        Path dir = Paths.get(basePath != null ? basePath : ".", ".license-tool");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
    }

    /**
     * Convenience accessor so other classes can consistently obtain the plugin logger.
     * Use LogInitializer.getLogger(YourClass.class) in your classes.
     */
    public static Logger getLogger(Class<?> cls) {
        return Logger.getInstance(cls);
    }

    // Lightweight Gson instance for structured state logging
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Emit a structured JSON state record to the plugin logger.
     * Example: LogInitializer.logState(getClass(), project, "pom_changed", Map.of("path", path));
     */
    public static void logState(Class<?> cls, com.intellij.openapi.project.Project project, String event, Map<String, Object> state) {
        try {
            Map<String,Object> payload = new HashMap<>();
            payload.put("ts", Instant.now().toEpochMilli());
            payload.put("project", project != null ? project.getName() : null);
            payload.put("event", event);
            payload.put("state", state != null ? state : new HashMap<>());
            String json = GSON.toJson(payload);
            Logger log = getLogger(cls);
            log.info("PLUGIN_STATE " + json);
        } catch (Throwable t) {
            // don't let logging blow up the host flow
            getLogger(LogInitializer.class).warn("Failed to emit structured state", t);
        }
    }

}
