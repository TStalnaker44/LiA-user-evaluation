package com.example.my_plugin;

import java.io.File;
import com.intellij.openapi.progress.ProgressIndicator;

// Creates a project-level service for the pom.xml listener.
// The service will be automatically created when the project opens and registers the listener.
public interface MavenDependencyService
{
    void flagNewDependency();

    default void flagNewDependency(ProgressIndicator indicator) {
        flagNewDependency();
    }

    default void flagNewDependency(ProgressIndicator indicator, String changedPomPath) {
        flagNewDependency(indicator);
    }

    // Generate SBOM for the project and return prev/current SBOM files (prev may be null).
    File[] genSbom();

    default File[] genSbom(ProgressIndicator indicator) {
        return genSbom();
    }

    default File[] genSbom(ProgressIndicator indicator, String changedPomPath) {
        return genSbom(indicator);
    }

}
