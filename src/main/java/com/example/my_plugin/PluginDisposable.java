// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file. (See home repository, https://github.com/JetBrains/intellij-community/blob/idea/251.25410.129/python/openapi/src/com/jetbrains/python/PythonPluginDisposable.java)

package com.example.my_plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

//The Disposer helps clean up memory, and services can't use the Project itself as a disposable root.
//This class will serve as a project-level disposable to govern resources expected to last the plugin's whole life.
//See: https://plugins.jetbrains.com/docs/intellij/disposers.html?from=IncorrectParentDisposable#choosing-a-disposable-parent, https://github.com/JetBrains/intellij-community/blob/idea/251.25410.129/python/openapi/src/com/jetbrains/python/PythonPluginDisposable.java
@Service({Service.Level.APP, Service.Level.PROJECT})
public final class PluginDisposable implements Disposable
{
    public static @NotNull Disposable getInstance() {
        return ApplicationManager.getApplication().getService(PluginDisposable.class);
    }

    public static @NotNull Disposable getInstance(@NotNull Project project) {
        return project.getService(PluginDisposable.class);
    }

    @Override
    public void dispose()
    {
        //See https://plugins.jetbrains.com/docs/intellij/disposers.html?from=jetbrains.org#automatically-disposed-objects
    }
}
