package com.requestjar.gui;

import com.requestjar.database.DatabaseManager;
import com.requestjar.database.Folder;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.List;

/**
 * Shown when "Send to RequestJar" is chosen from Burp's context menu.
 * Lets the user pick any collection or subfolder, create a new collection,
 * or add a subfolder to the currently selected node — then confirm.
 */
public class FolderSelectionDialog extends JDialog {

    private final DatabaseManager databaseManager;

    private JTree folderTree;
    private DefaultMutableTreeNode invisibleRoot;
    private DefaultTreeModel treeModel;

    private Folder selectedFolder = null;
    private boolean confirmed     = false;

    // Subfolder button is only enabled when a folder node is selected
    private JButton newSubfolderButton;

    public FolderSelectionDialog(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        setTitle("Save to RequestJar — Choose Destination");
        setModal(true);
        setSize(460, 380);
        setLocationRelativeTo(null);
        initializeUI();
        loadFolders();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void initializeUI() {
        setLayout(new BorderLayout(8, 8));

        JLabel lbl = new JLabel("Select a collection or subfolder, then click Save:");
        lbl.setBorder(BorderFactory.createEmptyBorder(10, 12, 2, 12));
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        add(lbl, BorderLayout.NORTH);

        add(buildTreePanel(),   BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        invisibleRoot = new DefaultMutableTreeNode("__root__");
        treeModel     = new DefaultTreeModel(invisibleRoot);
        folderTree    = new JTree(treeModel);
        folderTree.setRootVisible(false);
        folderTree.setShowsRootHandles(true);

        // Emoji as part of text — color emoji fonts render their own colors regardless
        // of the label foreground color, so this works in both light and dark Burp themes.
        folderTree.setCellRenderer(new DefaultTreeCellRenderer() {
            {
                setLeafIcon(null);
                setOpenIcon(null);
                setClosedIcon(null);
            }

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                    if (obj instanceof Folder) {
                        Folder f = (Folder) obj;
                        String prefix = f.getParentId() == null
                                ? "\uD83D\uDD78\uFE0F "  // 🕸️  collection
                                : "\uD83D\uDCC1 ";        // 📁  subfolder
                        setText(prefix + f.getName());
                        setIcon(null);
                    }
                }
                return this;
            }
        });

        folderTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) folderTree.getLastSelectedPathComponent();
            selectedFolder = (node != null && node.getUserObject() instanceof Folder)
                    ? (Folder) node.getUserObject() : null;
            newSubfolderButton.setEnabled(selectedFolder != null);
        });

        panel.add(new JScrollPane(folderTree), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildButtonPanel() {
        // ── Creation row ─────────────────────────────────────────────────
        JPanel createRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        createRow.setBorder(BorderFactory.createTitledBorder("Create new"));

        JButton newCollectionBtn = new JButton("📂 New Collection");
        newCollectionBtn.setToolTipText("Create a new top-level collection");
        newCollectionBtn.addActionListener(e -> createCollection());
        createRow.add(newCollectionBtn);

        newSubfolderButton = new JButton("📁 New Subfolder");
        newSubfolderButton.setToolTipText("Create a subfolder inside the selected collection/folder");
        newSubfolderButton.setEnabled(false);
        newSubfolderButton.addActionListener(e -> createSubfolder());
        createRow.add(newSubfolderButton);

        // ── Action row ────────────────────────────────────────────────────
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));

        JButton saveBtn = new JButton("💾 Save Here");
        saveBtn.addActionListener(e -> {
            if (selectedFolder == null) {
                JOptionPane.showMessageDialog(this,
                        "Please select a collection or subfolder first.",
                        "Nothing selected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });
        actionRow.add(saveBtn);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> { confirmed = false; selectedFolder = null; dispose(); });
        actionRow.add(cancelBtn);

        JPanel south = new JPanel(new BorderLayout());
        south.add(createRow,  BorderLayout.CENTER);
        south.add(actionRow,  BorderLayout.SOUTH);
        return south;
    }

    // ── Tree build / reload ───────────────────────────────────────────────

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
                else invisibleRoot.add(node); // fallback
            }
        }

        treeModel.reload();
        // Expand all rows so the full hierarchy is visible in this dialog
        for (int i = 0; i < folderTree.getRowCount(); i++) folderTree.expandRow(i);

        // Auto-select first collection if available
        if (invisibleRoot.getChildCount() > 0) {
            DefaultMutableTreeNode first = (DefaultMutableTreeNode) invisibleRoot.getChildAt(0);
            TreePath path = new TreePath(first.getPath());
            folderTree.setSelectionPath(path);
        }
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

    // ── Folder creation ───────────────────────────────────────────────────

    private void createCollection() {
        String name = promptName("New Collection", "Collection name:");
        if (name == null) return;
        Folder f = databaseManager.createFolder(name, null);
        if (f != null) {
            loadFolders();
            // Auto-select the newly created collection
            selectFolderById(f.getId());
        } else {
            JOptionPane.showMessageDialog(this, "Failed to create collection.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createSubfolder() {
        if (selectedFolder == null) return;
        String ctx  = selectedFolder.getName();
        String name = promptName("New Subfolder in \"" + ctx + "\"", "Subfolder name:");
        if (name == null) return;
        Folder f = databaseManager.createFolder(name, selectedFolder.getId());
        if (f != null) {
            loadFolders();
            selectFolderById(f.getId());
        } else {
            JOptionPane.showMessageDialog(this, "Failed to create subfolder.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Prompt for a folder name. Uses showInputDialog so pressing Enter
     * always confirms (not accidentally closes) the dialog.
     */
    private String promptName(String title, String label) {
        String input = JOptionPane.showInputDialog(this, label, title, JOptionPane.PLAIN_MESSAGE);
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return s;
    }

    /** After reload, re-select a folder by its DB id. */
    private void selectFolderById(int id) {
        DefaultMutableTreeNode node = findNode(invisibleRoot, id);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            folderTree.setSelectionPath(path);
            folderTree.scrollPathToVisible(path);
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Folder getSelectedFolder() { return confirmed ? selectedFolder : null; }
    public boolean isConfirmed()       { return confirmed; }
}
