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

package com.leinardi.pycharm.mypy.toolwindow;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.leinardi.pycharm.mypy.MypyBundle;
import com.leinardi.pycharm.mypy.MypyPlugin;
import com.leinardi.pycharm.mypy.checker.Problem;
import com.leinardi.pycharm.mypy.exception.MypyToolException;
import com.leinardi.pycharm.mypy.mpapi.SeverityLevel;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang.StringUtils.isBlank;

/**
 * The tool window for Mypy scans.
 */
public class MypyToolWindowPanel extends JPanel implements DumbAware {

    public static final String ID_TOOLWINDOW = "Mypy";

    /**
     * Logger for this class.
     */
    private static final Logger LOG = Logger.getInstance(MypyToolWindowPanel.class);

    private static final String MAIN_ACTION_GROUP = "MypyPluginActions";
    private static final String TREE_ACTION_GROUP = "MypyPluginTreeActions";
    private static final Map<Pattern, String> MYPY_ERROR_PATTERNS
            = new HashMap<>();

    private final MypyPlugin mypyPlugin;
    private final Project project;
    private final ToolWindow toolWindow;

    private boolean displayingErrors = true;
    private boolean displayingWarnings = true;
    private boolean displayingNotes = true;

    private JTree resultsTree;
    private JToolBar progressPanel;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    private ResultTreeModel treeModel;
    private boolean scrollToSource;

    static {
        try {
            MYPY_ERROR_PATTERNS.put(
                    Pattern.compile("Property \\$\\{([^\\}]*)\\} has not been set"),
                    "plugin.results.error.missing-property");
            MYPY_ERROR_PATTERNS.put(
                    Pattern.compile("Unable to instantiate (.*)"),
                    "plugin.results.error.instantiation-failed");

        } catch (Throwable t) {
            LOG.warn("Pattern mappings could not be instantiated.", t);
        }
    }

    /**
     * Create a tool window for the given project.
     *
     * @param project the project.
     */
    public MypyToolWindowPanel(final ToolWindow toolWindow, final Project project) {
        super(new BorderLayout());

        this.toolWindow = toolWindow;
        this.project = project;

        mypyPlugin = project.getService(MypyPlugin.class);
        if (mypyPlugin == null) {
            throw new IllegalStateException("Couldn't get mypy plugin");
        }

        final ActionGroup mainActionGroup = (ActionGroup)
                ActionManager.getInstance().getAction(MAIN_ACTION_GROUP);
        final ActionToolbar mainToolbar = ActionManager.getInstance().createActionToolbar(
                ID_TOOLWINDOW, mainActionGroup, false);
        mainToolbar.setTargetComponent(this);

        final ActionGroup treeActionGroup = (ActionGroup)
                ActionManager.getInstance().getAction(TREE_ACTION_GROUP);
        final ActionToolbar treeToolbar = ActionManager.getInstance().createActionToolbar(
                ID_TOOLWINDOW, treeActionGroup, false);
        treeToolbar.setTargetComponent(this);

        final Box toolBarBox = Box.createHorizontalBox();
        toolBarBox.add(mainToolbar.getComponent());
        toolBarBox.add(treeToolbar.getComponent());

        setBorder(JBUI.Borders.empty(1));
        add(toolBarBox, BorderLayout.WEST);
        add(createToolPanel(), BorderLayout.CENTER);

        expandTree();

        mainToolbar.getComponent().setVisible(true);
    }

    private JPanel createToolPanel() {
        treeModel = new ResultTreeModel();

        resultsTree = new Tree(treeModel);
        resultsTree.setRootVisible(false);

        final TreeSelectionListener treeSelectionListener = new ToolWindowSelectionListener();
        resultsTree.addTreeSelectionListener(treeSelectionListener);
        final MouseListener treeMouseListener = new ToolWindowMouseListener();
        resultsTree.addMouseListener(treeMouseListener);
        resultsTree.addKeyListener(new ToolWindowKeyboardListener());
        resultsTree.setCellRenderer(new ResultTreeRenderer());

        progressLabel = new JLabel(" ");
        progressBar = new JProgressBar(JProgressBar.HORIZONTAL);
        progressBar.setMinimum(0);
        final Dimension progressBarSize = new Dimension(100, progressBar.getPreferredSize().height);
        progressBar.setMinimumSize(progressBarSize);
        progressBar.setPreferredSize(progressBarSize);
        progressBar.setMaximumSize(progressBarSize);

        progressPanel = new JToolBar(JToolBar.HORIZONTAL);
        progressPanel.add(Box.createHorizontalStrut(4));
        progressPanel.addSeparator();
        progressPanel.add(Box.createHorizontalStrut(4));
        progressPanel.add(progressLabel);
        progressPanel.add(Box.createHorizontalGlue());
        progressPanel.setFloatable(false);
        progressPanel.setOpaque(false);
        progressPanel.setBorder(null);

        final JPanel toolPanel = new JPanel(new BorderLayout());
        toolPanel.add(new JBScrollPane(resultsTree), BorderLayout.CENTER);
        toolPanel.add(progressPanel, BorderLayout.NORTH);

        ToolTipManager.sharedInstance().registerComponent(resultsTree);

        return toolPanel;
    }

    @Nullable
    public static MypyToolWindowPanel panelFor(final Project project) {
        final ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        if (toolWindowManager == null) {
            LOG.debug("Couldn't get tool window manager for project " + project);
            return null;
        }

        final ToolWindow toolWindow = toolWindowManager.getToolWindow(ID_TOOLWINDOW);
        if (toolWindow == null) {
            LOG.debug("Couldn't get tool window for ID " + ID_TOOLWINDOW);
            return null;
        }

        for (Content currentContent : toolWindow.getContentManager().getContents()) {
            if (currentContent.getComponent() instanceof MypyToolWindowPanel) {
                return (MypyToolWindowPanel) currentContent.getComponent();
            }
        }

        LOG.debug("Could not find tool window panel on tool window with ID " + ID_TOOLWINDOW);
        return null;
    }

    public void showToolWindow() {
        toolWindow.show(null);
    }

    /**
     * Update the progress text.
     *
     * @param text the new progress text, or null to clear.
     */
    public void setProgressText(@Nullable final String text) {
        if (isBlank(text)) {
            progressLabel.setText(" ");
        } else {
            progressLabel.setText(text);
        }
        progressLabel.validate();
    }

    /**
     * Show and reset the progress bar.
     */
    private void resetProgressBar() {
        progressBar.setValue(0);

        // show if necessary
        if (progressPanel.getComponentIndex(progressBar) == -1) {
            progressPanel.add(progressBar);
        }

        progressPanel.revalidate();
    }

    /**
     * Set the maxium limit, then show and reset the progress bar.
     *
     * @param max the maximum limit of the progress bar.
     */
    private void setProgressBarMax(final int max) {
        progressBar.setMaximum(max);
        resetProgressBar();
    }

    /**
     * Increment the progress of the progress bar by a given number.
     * <p>
     * You should call {@link #displayInProgress(int)} first for useful semantics.
     *
     * @param size the size to increment by.
     */
    public void incrementProgressBarBy(final int size) {
        if (progressBar.getValue() < progressBar.getMaximum()) {
            progressBar.setValue(progressBar.getValue() + size);
        }
    }

    private void clearProgress() {
        final int progressIndex = progressPanel.getComponentIndex(progressBar);
        if (progressIndex != -1) {
            progressPanel.remove(progressIndex);
            progressPanel.revalidate();
            progressPanel.repaint();
        }
        setProgressText(null);
    }

    /**
     * Scroll to the error specified by the given tree path, or do nothing
     * if no error is specified.
     *
     * @param treePath the tree path to scroll to.
     */
    private void scrollToError(final TreePath treePath) {
        final DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) treePath.getLastPathComponent();
        if (treeNode == null || !(treeNode.getUserObject() instanceof ResultTreeNode)) {
            return;
        }

        final ResultTreeNode nodeInfo = (ResultTreeNode) treeNode.getUserObject();
        if (nodeInfo.getFile() == null || nodeInfo.getProblem() == null) {
            return; // no problem here :-)
        }

        final VirtualFile virtualFile = nodeInfo.getFile().getVirtualFile();
        if (virtualFile == null || !virtualFile.exists()) {
            return;
        }

        final FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
        ApplicationManager.getApplication().invokeLater(() -> {
            final FileEditor[] editor = fileEditorManager.openFile(
                    virtualFile, true);

            if (editor.length > 0 && editor[0] instanceof TextEditor) {
                final LogicalPosition problemPos = new LogicalPosition(
                        Math.max(lineFor(nodeInfo) - 1, 0), Math.max(columnFor(nodeInfo), 0));

                final Editor textEditor = ((TextEditor) editor[0]).getEditor();
                textEditor.getCaretModel().moveToLogicalPosition(problemPos);
                textEditor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        }, ModalityState.NON_MODAL);
    }

    private int lineFor(final ResultTreeNode nodeInfo) {
        return nodeInfo.getProblem().line();
    }

    private int columnFor(final ResultTreeNode nodeInfo) {
        return nodeInfo.getProblem().column();
    }

    /**
     * Should we scroll to the selected error in the editor automatically?
     *
     * @param scrollToSource true if the error should be scrolled to automatically.
     */
    public void setScrollToSource(final boolean scrollToSource) {
        this.scrollToSource = scrollToSource;
    }

    /**
     * Should we scroll to the selected error in the editor automatically?
     *
     * @return true if the error should be scrolled to automatically.
     */
    public boolean isScrollToSource() {
        return scrollToSource;
    }

    /**
     * Listen for clicks and scroll to the error's source as necessary.
     */
    protected class ToolWindowMouseListener extends MouseAdapter {

        @Override
        public void mouseClicked(final MouseEvent e) {
            if (!scrollToSource && e.getClickCount() < 2) {
                return;
            }

            final TreePath treePath = resultsTree.getPathForLocation(
                    e.getX(), e.getY());

            if (treePath != null) {
                scrollToError(treePath);
            }
        }

    }

    /**
     * Listen for Enter key being pressed and scroll to the error's source
     */
    protected class ToolWindowKeyboardListener extends KeyAdapter {

        @Override
        public void keyPressed(final KeyEvent e) {
            if (e.getKeyCode() != KeyEvent.VK_ENTER) {
                return;
            }

            final TreePath treePath = resultsTree.getSelectionPath();

            if (treePath != null) {
                scrollToError(treePath);
            }
        }
    }

    /**
     * Listen for tree selection events and scroll to the error's source as necessary.
     */
    protected class ToolWindowSelectionListener implements TreeSelectionListener {

        @Override
        public void valueChanged(final TreeSelectionEvent e) {
            if (!scrollToSource) {
                return;
            }

            if (e.getPath() != null) {
                scrollToError(e.getPath());
            }
        }

    }

    /**
     * Collapse the tree so that only the root node is visible.
     */
    public void collapseTree() {
        for (int i = 1; i < resultsTree.getRowCount(); ++i) {
            resultsTree.collapseRow(i);
        }
    }

    /**
     * Expand the error tree to the fullest.
     */
    public void expandTree() {
        expandTree(3);
    }

    /**
     * Expand the given tree to the given level, starting from the given node
     * and path.
     *
     * @param tree  The tree to be expanded
     * @param node  The node to start from
     * @param path  The path to start from
     * @param level The number of levels to expand to
     */
    private static void expandNode(final JTree tree,
                                   final TreeNode node,
                                   final TreePath path,
                                   final int level) {
        if (level <= 0) {
            return;
        }

        tree.expandPath(path);

        for (int i = 0; i < node.getChildCount(); ++i) {
            final TreeNode childNode = node.getChildAt(i);
            expandNode(tree, childNode, path.pathByAddingChild(childNode), level - 1);
        }
    }

    /**
     * Expand the error tree to the given level.
     *
     * @param level The level to expand to
     */
    private void expandTree(final int level) {
        expandNode(resultsTree, treeModel.getVisibleRoot(),
                new TreePath(treeModel.getPathToRoot(treeModel.getVisibleRoot())), level);
    }

    /**
     * Clear the results and display a 'scan in progress' notice.
     *
     * @param size the number of files being scanned.
     */
    public void displayInProgress(final int size) {
        setProgressBarMax(size);

        treeModel.clear();
        treeModel.setRootMessage("plugin.results.in-progress");
    }

    public void displayWarningResult(final String messageKey,
                                     final Object... messageArgs) {
        clearProgress();

        treeModel.clear();
        treeModel.setRootMessage(messageKey, messageArgs);
    }

    public void clearResult() {
        treeModel.clear();
        treeModel.setRootText(null);
    }

    /**
     * Clear the results and display notice to say an error occurred.
     *
     * @param error the error that occurred.
     */
    public void displayErrorResult(final Throwable error) {
        // match some friendly error messages.
        String errorText = null;
        if (error instanceof MypyToolException && error.getCause() != null) {
            for (final Map.Entry<Pattern, String> errorPatternEntry
                    : MYPY_ERROR_PATTERNS.entrySet()) {
                final Matcher errorMatcher
                        = errorPatternEntry.getKey().matcher(error.getCause().getMessage());
                if (errorMatcher.find()) {
                    final Object[] args = new Object[errorMatcher.groupCount()];

                    for (int i = 0; i < errorMatcher.groupCount(); ++i) {
                        args[i] = errorMatcher.group(i + 1);
                    }

                    errorText = MypyBundle.message(errorPatternEntry.getValue(), args);
                }
            }
        }

        if (errorText == null) {
            errorText = MypyBundle.message("plugin.results.error");
        }

        treeModel.clear();
        treeModel.setRootText(errorText);

        clearProgress();
    }

    private SeverityLevel[] getDisplayedSeverities() {
        final List<SeverityLevel> severityLevels = new ArrayList<>();
        if (displayingErrors) {
            severityLevels.add(SeverityLevel.ERROR);
        }
        if (displayingWarnings) {
            severityLevels.add(SeverityLevel.WARNING);
        }
        if (displayingNotes) {
            severityLevels.add(SeverityLevel.NOTE);
        }
        return severityLevels.toArray(new SeverityLevel[0]);
    }

    /**
     * Refresh the displayed results based on the current filter settings.
     */
    public void filterDisplayedResults() {
        // TODO be a little nicer here, maintain display state

        treeModel.filter(getDisplayedSeverities());
    }

    /**
     * Display the passed results.
     *
     * @param results the map of checked files to problem descriptors.
     */
    public void displayResults(final Map<PsiFile, List<Problem>> results) {
        treeModel.setModel(results, getDisplayedSeverities());

        invalidate();
        repaint();

        expandTree();
        clearProgress();
    }

    public boolean isDisplayingErrors() {
        return displayingErrors;
    }

    public void setDisplayingErrors(final boolean displayingErrors) {
        this.displayingErrors = displayingErrors;
    }

    public boolean isDisplayingWarnings() {
        return displayingWarnings;
    }

    public void setDisplayingWarnings(final boolean displayingWarnings) {
        this.displayingWarnings = displayingWarnings;
    }

    public boolean isDisplayingNotes() {
        return displayingNotes;
    }

    public void setDisplayingNotes(final boolean displayingNotes) {
        this.displayingNotes = displayingNotes;
    }

}
