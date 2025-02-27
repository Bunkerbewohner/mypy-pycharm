/*
 * Copyright 2021 Roberto Leinardi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.leinardi.pycharm.mypy.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.leinardi.pycharm.mypy.toolwindow.MypyToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static com.leinardi.pycharm.mypy.actions.ToolWindowAccess.actOnToolWindowPanel;
import static com.leinardi.pycharm.mypy.actions.ToolWindowAccess.getFromToolWindowPanel;
import static com.leinardi.pycharm.mypy.actions.ToolWindowAccess.toolWindow;

/**
 * Action to toggle error display in tool window.
 */
public class DisplayWarnings extends DumbAwareToggleAction {

    @Override
    public boolean isSelected(final @NotNull AnActionEvent event) {
        final Project project = getEventProject(event);
        if (project == null) {
            return false;
        }

        Boolean displayingWarnings = getFromToolWindowPanel(toolWindow(project),
                MypyToolWindowPanel::isDisplayingWarnings);
        return Objects.requireNonNullElse(displayingWarnings, false);
    }

    @Override
    public void setSelected(final @NotNull AnActionEvent event, final boolean selected) {
        final Project project = getEventProject(event);
        if (project == null) {
            return;
        }

        actOnToolWindowPanel(toolWindow(project), panel -> {
            panel.setDisplayingWarnings(selected);
            panel.filterDisplayedResults();
        });
    }
}
