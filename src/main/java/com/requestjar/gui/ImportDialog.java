package com.requestjar.gui;

import com.requestjar.database.DatabaseManager;
import com.requestjar.database.Folder;
import com.requestjar.database.Request;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Reads a RequestJar JSON export file and recreates its collections,
 * subfolders, and requests in the local database.
 */
public class ImportDialog extends JDialog {

    private final DatabaseManager databaseManager;
    private boolean imported = false;

    private JLabel statusLabel;
    private JTextArea logArea;

    public ImportDialog(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        setTitle("Import Collections");
        setModal(true);
        setSize(560, 380);
        setLocationRelativeTo(null);
        initializeUI();
    }

    private void initializeUI() {
        setLayout(new BorderLayout(8, 8));
        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(new EmptyBorder(12, 12, 12, 12));

        statusLabel = new JLabel("Choose a RequestJar JSON file to import.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        main.add(statusLabel, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        main.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        JButton browseBtn = new JButton("Browse & Import…");
        browseBtn.addActionListener(e -> browseAndImport());
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        btnPanel.add(browseBtn);
        btnPanel.add(closeBtn);
        main.add(btnPanel, BorderLayout.SOUTH);

        add(main);
    }

    private void browseAndImport() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("RequestJar JSON (*.json)", "json"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = fc.getSelectedFile();
        logArea.setText("");
        log("Reading: " + file.getAbsolutePath());

        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(file, Map.class);

            String fmt = (String) root.get("format");
            if (!"requestjar-v1".equals(fmt)) {
                log("⚠ Unknown format '" + fmt + "' — attempting import anyway.");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> collections =
                    (List<Map<String, Object>>) root.get("collections");

            if (collections == null || collections.isEmpty()) {
                log("No collections found in file.");
                statusLabel.setText("Import finished — nothing to import.");
                return;
            }

            int collectionCount = 0, requestCount = 0;
            for (Map<String, Object> col : collections) {
                int[] counts = importFolder(col, null, 0);
                collectionCount += counts[0];
                requestCount  += counts[1];
            }

            imported = true;
            statusLabel.setText("✔ Imported " + collectionCount + " folder(s) and " + requestCount + " request(s).");
            log("Done.");

        } catch (Exception ex) {
            log("Error: " + ex.getMessage());
            statusLabel.setText("Import failed.");
            JOptionPane.showMessageDialog(this, "Import failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Recursively imports a folder node. Returns [folderCount, requestCount].
     */
    @SuppressWarnings("unchecked")
    private int[] importFolder(Map<String, Object> node, Integer parentId, int depth) {
        String name = (String) node.get("name");
        if (name == null || name.isBlank()) return new int[]{0, 0};

        Folder created = databaseManager.createFolder(name, parentId);
        if (created == null) {
            log("  ✗ Failed to create folder: " + name);
            return new int[]{0, 0};
        }
        log("  " + "  ".repeat(depth) + "📁 " + name + " (id=" + created.getId() + ")");

        int folderCount = 1, requestCount = 0;

        // Import requests inside this folder
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) node.get("requests");
        if (reqs != null) {
            for (Map<String, Object> rMap : reqs) {
                Request r = new Request();
                r.setFolderId(created.getId());
                r.setMethod(str(rMap, "method", "GET"));
                r.setUrl(str(rMap, "url", ""));
                r.setHeaders(str(rMap, "headers", ""));
                r.setBody(str(rMap, "body", ""));
                r.setFullRequest(str(rMap, "full_request", ""));
                r.setTags(str(rMap, "tags", ""));
                Object ts = rMap.get("created_at");
                r.setCreatedAt(ts instanceof Number ? ((Number) ts).longValue() : System.currentTimeMillis());
                if (databaseManager.saveRequest(r)) {
                    requestCount++;
                    log("    " + "  ".repeat(depth) + "↳ " + r.getMethod() + " " + r.getUrl());
                }
            }
        }

        // Recurse into subfolders
        List<Map<String, Object>> subs = (List<Map<String, Object>>) node.get("subfolders");
        if (subs != null) {
            for (Map<String, Object> sub : subs) {
                int[] childCounts = importFolder(sub, created.getId(), depth + 1);
                folderCount  += childCounts[0];
                requestCount += childCounts[1];
            }
        }

        return new int[]{folderCount, requestCount};
    }

    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }

    private void log(String msg) {
        logArea.append(msg + "\n");
    }

    public boolean wasImported() { return imported; }
}
