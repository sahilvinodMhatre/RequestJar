package com.requestjar.gui;

import burp.IBurpExtenderCallbacks;
import com.requestjar.database.DatabaseManager;
import com.requestjar.database.Folder;
import com.requestjar.database.Request;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

public class RequestJarTab extends JPanel {

    private final IBurpExtenderCallbacks callbacks;
    private final DatabaseManager databaseManager;

    private JTree folderTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode invisibleRoot;

    private RequestViewerPanel requestViewerPanel;

    // Track the currently selected top-level collection so "Export" knows what to export
    private Folder selectedCollection = null;

    public RequestJarTab(IBurpExtenderCallbacks callbacks, DatabaseManager databaseManager) {
        this.callbacks = callbacks;
        this.databaseManager = databaseManager;
        initializeUI();
        loadFolders();
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(5, 5, 5, 5));
        add(createToolBar(), BorderLayout.NORTH);
        add(createMainContent(), BorderLayout.CENTER);
    }

    private JToolBar createToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        bar.add(makeToolButton("\uD83D\uDCC2 New Collection", e -> showNewCollectionDialog()));  // 📂
        bar.addSeparator();
        bar.add(makeToolButton("\uD83D\uDCE4 Export", e -> showExportSelectedDialog()));         // 📤
        bar.add(makeToolButton("\uD83D\uDCE5 Import", e -> showImportDialog()));                 // 📥
        bar.addSeparator();
        bar.add(makeToolButton("\uD83D\uDD04 Refresh", e -> refreshRequestTree()));              // 🔄
        bar.add(makeToolButton("\uD83D\uDDC2 Export All Collections", e -> showExportAllDialog())); // 🗂

        return bar;
    }

    private JButton makeToolButton(String label, ActionListener al) {
        JButton b = new JButton(label);
        b.setFocusPainted(false);
        b.addActionListener(al);
        return b;
    }

    private JSplitPane createMainContent() {
        requestViewerPanel = new RequestViewerPanel(callbacks, databaseManager);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createFolderTreePanel(), requestViewerPanel);
        split.setDividerLocation(240);
        return split;
    }

    // ── Folder Tree ───────────────────────────────────────────────────────

    private JPanel createFolderTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Collections"));

        invisibleRoot = new DefaultMutableTreeNode("__root__");
        treeModel = new DefaultTreeModel(invisibleRoot);
        folderTree = new JTree(treeModel);
        folderTree.setRootVisible(false);
        folderTree.setShowsRootHandles(true);

        // ── Custom cell renderer: emoji prefix in text (works in any L&F / theme) ──
        folderTree.setCellRenderer(new DefaultTreeCellRenderer() {
            {
                // Remove the default JTree folder/leaf icons so only our emoji shows
                setLeafIcon(null);
                setOpenIcon(null);
                setClosedIcon(null);
            }

            @Override
            public Component getTreeCellRendererComponent(
                    JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {

                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

                if (value instanceof DefaultMutableTreeNode) {
                    Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                    if (obj instanceof Folder) {
                        Folder f = (Folder) obj;
                        // Emoji is part of the label text — color emoji fonts render
                        // with their own intrinsic colors regardless of label foreground,
                        // so this works correctly in both light and dark L&Fs.
                        String prefix = f.getParentId() == null
                                ? "\uD83D\uDD78\uFE0F "   // 🕸️  collection
                                : "\uD83D\uDCC1 ";         // 📁  subfolder
                        setText(prefix + f.getName());
                        setIcon(null);
                    }
                }
                return this;
            }
        });

        folderTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath path = folderTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) folderTree.setSelectionPath(path);
                    showTreeContextMenu(e);
                }
            }
        });

        folderTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) folderTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof Folder) {
                Folder f = (Folder) node.getUserObject();
                loadRequestsForFolder(f);
                // Remember the top-level collection for "Export"
                selectedCollection = getRootCollection(node);
            } else {
                selectedCollection = null;
            }
        });

        panel.add(new JScrollPane(folderTree), BorderLayout.CENTER);
        return panel;
    }

    /** Walk up the tree to find the top-level (collection) ancestor of a node. */
    private Folder getRootCollection(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode cursor = node;
        while (cursor.getParent() != null && cursor.getParent() != invisibleRoot
                && ((DefaultMutableTreeNode) cursor.getParent()).getUserObject() instanceof Folder) {
            cursor = (DefaultMutableTreeNode) cursor.getParent();
        }
        Object obj = cursor.getUserObject();
        return obj instanceof Folder ? (Folder) obj : null;
    }

    private void showTreeContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();

        DefaultMutableTreeNode selected =
                (DefaultMutableTreeNode) folderTree.getLastSelectedPathComponent();
        boolean folderSelected = selected != null && selected.getUserObject() instanceof Folder;

        JMenuItem newCollection = new JMenuItem("\uD83D\uDCC2 New Collection");
        newCollection.addActionListener(ev -> showNewCollectionDialog());
        menu.add(newCollection);

        JMenuItem newSub = new JMenuItem("\uD83D\uDCC1 New Subfolder");
        newSub.setEnabled(folderSelected);
        newSub.addActionListener(ev -> {
            if (folderSelected) showNewSubfolderDialog((Folder) selected.getUserObject());
        });
        menu.add(newSub);

        menu.addSeparator();

        JMenuItem exportCol = new JMenuItem("\uD83D\uDCE4 Export This Collection");
        exportCol.setEnabled(folderSelected);
        exportCol.addActionListener(ev -> {
            if (folderSelected) {
                Folder col = getRootCollection(selected);
                if (col != null) new ExportDialog(databaseManager, col).setVisible(true);
            }
        });
        menu.add(exportCol);

        menu.addSeparator();

        JMenuItem delete = new JMenuItem("\uD83D\uDDD1 Delete");
        delete.setEnabled(folderSelected);
        delete.addActionListener(ev -> {
            if (folderSelected) deleteFolder((Folder) selected.getUserObject());
        });
        menu.add(delete);

        menu.show(folderTree, e.getX(), e.getY());
    }

    // ── Tree Building ─────────────────────────────────────────────────────

    private void loadFolders() {
        List<Folder> folders = databaseManager.getAllFolders();
        invisibleRoot.removeAllChildren();

        for (Folder folder : folders) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(folder);
            if (folder.getParentId() == null) {
                invisibleRoot.add(node);
            } else {
                DefaultMutableTreeNode parent = findNode(invisibleRoot, folder.getParentId());
                if (parent != null) parent.add(node);
                else invisibleRoot.add(node);
            }
        }

        treeModel.reload();
        // Leave collections collapsed so the hierarchy is immediately clear.
        // Users can click to expand any collection they want to browse.
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode root, int targetId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof Folder) {
                if (((Folder) child.getUserObject()).getId() == targetId) return child;
                DefaultMutableTreeNode found = findNode(child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void loadRequestsForFolder(Folder folder) {
        List<Request> requests = databaseManager.getRequestsByFolder(folder.getId());
        requestViewerPanel.displayRequests(requests, folder);
    }

    // ── Folder CRUD ───────────────────────────────────────────────────────

    private void showNewCollectionDialog() {
        String name = promptName("New Collection", "Collection Name:");
        if (name != null) {
            Folder f = databaseManager.createFolder(name, null);
            if (f != null) { refreshRequestTree(); callbacks.printOutput("Created collection: " + name); }
            else JOptionPane.showMessageDialog(this, "Failed to create collection.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showNewSubfolderDialog(Folder parent) {
        String name = promptName("New Subfolder in \"" + parent.getName() + "\"", "Subfolder Name:");
        if (name != null) {
            Folder f = databaseManager.createFolder(name, parent.getId());
            if (f != null) { refreshRequestTree(); callbacks.printOutput("Created subfolder: " + name); }
            else JOptionPane.showMessageDialog(this, "Failed to create subfolder.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteFolder(Folder folder) {
        int ans = JOptionPane.showConfirmDialog(this,
                "Delete \"" + folder.getName() + "\" and all its requests?",
                "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans == JOptionPane.YES_OPTION && databaseManager.deleteFolder(folder.getId())) {
            refreshRequestTree();
            callbacks.printOutput("Deleted: " + folder.getName());
        }
    }

    /**
     * Prompt the user for a name using showInputDialog.
     * Using showInputDialog (not showConfirmDialog+JTextField) ensures
     * pressing Enter always confirms — not closes — the popup.
     */
    private String promptName(String title, String label) {
        String input = JOptionPane.showInputDialog(this, label, title, JOptionPane.PLAIN_MESSAGE);
        if (input == null) return null; // cancelled via Cancel or X
        String s = input.trim();
        if (s.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return s;
    }

    // ── Export / Import ───────────────────────────────────────────────────

    /** Export only the currently selected collection. */
    private void showExportSelectedDialog() {
        if (selectedCollection == null) {
            JOptionPane.showMessageDialog(this,
                    "Please select a collection in the left panel first.",
                    "No Collection Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        new ExportDialog(databaseManager, selectedCollection).setVisible(true);
    }

    /** Export every collection at once. */
    private void showExportAllDialog() {
        new ExportDialog(databaseManager, null).setVisible(true);
    }

    private void showImportDialog() {
        ImportDialog dlg = new ImportDialog(databaseManager);
        dlg.setVisible(true);
        if (dlg.wasImported()) refreshRequestTree();
    }

    // ── Public ────────────────────────────────────────────────────────────

    public void refreshRequestTree() {
        loadFolders();
    }
}
