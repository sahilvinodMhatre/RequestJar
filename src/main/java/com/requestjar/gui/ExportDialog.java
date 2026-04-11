package com.requestjar.gui;

import com.requestjar.database.DatabaseManager;
import com.requestjar.database.Folder;
import com.requestjar.database.Request;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * JSON-only export dialog. CSV export was removed to eliminate
 * formula-injection (CWE-1236) risk entirely.
 */
public class ExportDialog extends JDialog {

    private final DatabaseManager databaseManager;
    /** null = export all collections; non-null = export only this collection */
    private final Folder targetCollection;
    private JTextArea previewArea;

    /** Export ALL collections. */
    public ExportDialog(DatabaseManager databaseManager) {
        this(databaseManager, null);
    }

    /** Export a single collection, or all if folder is null. */
    public ExportDialog(DatabaseManager databaseManager, Folder folder) {
        this.databaseManager = databaseManager;
        this.targetCollection = folder;
        String title = (folder == null)
                ? "Export All Collections"
                : "Export Collection: \"" + folder.getName() + "\"";
        setTitle(title);
        setModal(true);
        setSize(640, 440);
        setLocationRelativeTo(null);
        initializeUI();
        generatePreview();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        JPanel main = new JPanel(new BorderLayout());
        main.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel formatLabel = new JLabel("Export format: JSON (full collection hierarchy)");
        formatLabel.setBorder(new EmptyBorder(5, 5, 10, 5));
        formatLabel.setFont(formatLabel.getFont().deriveFont(Font.BOLD));
        main.add(formatLabel, BorderLayout.NORTH);

        main.add(createPreviewPanel(), BorderLayout.CENTER);
        main.add(createButtonPanel(), BorderLayout.SOUTH);
        add(main);
    }

    private JPanel createPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Preview"));
        previewArea = new JTextArea();
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(previewArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout());
        JButton exportBtn = new JButton("Export to File");
        exportBtn.addActionListener(e -> exportToFile());
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        panel.add(exportBtn);
        panel.add(cancelBtn);
        return panel;
    }

    // ── Preview ───────────────────────────────────────────────────────────

    private void generatePreview() {
        try {
            String json = buildJson();
            previewArea.setText(json.length() > 3000
                    ? json.substring(0, 3000) + "\n... (truncated)" : json);
        } catch (Exception e) {
            previewArea.setText("Error: " + e.getMessage());
        }
    }

    // ── Export ────────────────────────────────────────────────────────────

    private void exportToFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("RequestJar JSON (*.json)", "json"));
        fc.setSelectedFile(new File("requestjar_export_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = fc.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".json"))
            file = new File(file.getParent(), file.getName() + ".json");

        try (FileWriter w = new FileWriter(file)) {
            w.write(buildJson());
            JOptionPane.showMessageDialog(this,
                    "Exported to: " + file.getAbsolutePath(),
                    "Export Successful", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Export failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── JSON (full hierarchy) ─────────────────────────────────────────────

    private String buildJson() throws Exception {
        List<Folder> folders = databaseManager.getAllFolders();
        List<Request> allRequests = databaseManager.getAllRequests();

        List<Map<String, Object>> collections = new ArrayList<>();
        for (Folder f : folders) {
            if (f.getParentId() != null) continue;
            if (targetCollection != null && f.getId() != targetCollection.getId()) continue;
            collections.add(buildFolderNode(f, folders, allRequests));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "requestjar-v1");
        root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        root.put("collections", collections);

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(root);
    }

    private Map<String, Object> buildFolderNode(Folder folder, List<Folder> all, List<Request> allReqs) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", folder.getId());
        node.put("name", folder.getName());
        node.put("created_at", folder.getCreatedAt());

        // Subfolders — F-09 fix: use .intValue() instead of == for Integer comparison
        List<Map<String, Object>> subs = new ArrayList<>();
        for (Folder f : all) {
            if (f.getParentId() != null && f.getParentId().intValue() == folder.getId()) {
                subs.add(buildFolderNode(f, all, allReqs));
            }
        }
        node.put("subfolders", subs);

        // Requests (including response data)
        List<Map<String, Object>> reqs = new ArrayList<>();
        for (Request r : allReqs) {
            if (r.getFolderId() == folder.getId()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r.getId());
                rm.put("method", r.getMethod());
                rm.put("url", r.getUrl());
                rm.put("headers", r.getHeaders());
                rm.put("body", r.getBody());
                rm.put("full_request", r.getFullRequest());
                rm.put("response", r.getResponse());
                rm.put("tags", r.getTags());
                rm.put("created_at", r.getCreatedAt());
                rm.put("host", r.getHost());
                rm.put("port", r.getPort());
                rm.put("protocol", r.getProtocol());
                reqs.add(rm);
            }
        }
        node.put("requests", reqs);
        return node;
    }
}
