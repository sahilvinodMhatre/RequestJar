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

public class ExportDialog extends JDialog {

    private final DatabaseManager databaseManager;
    /** null = export all collections; non-null = export only this collection */
    private final Folder targetCollection;
    private JRadioButton jsonRadioButton;
    private JRadioButton csvRadioButton;
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
        main.add(createFormatPanel(), BorderLayout.NORTH);
        main.add(createPreviewPanel(), BorderLayout.CENTER);
        main.add(createButtonPanel(), BorderLayout.SOUTH);
        add(main);
    }

    private JPanel createFormatPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Export Format"));
        jsonRadioButton = new JRadioButton("JSON (full collection hierarchy)", true);
        csvRadioButton  = new JRadioButton("CSV (flat request list)");
        ButtonGroup g = new ButtonGroup();
        g.add(jsonRadioButton); g.add(csvRadioButton);
        jsonRadioButton.addActionListener(e -> generatePreview());
        csvRadioButton.addActionListener(e  -> generatePreview());
        panel.add(jsonRadioButton);
        panel.add(csvRadioButton);
        return panel;
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
        if (jsonRadioButton.isSelected()) previewJson();
        else previewCsv();
    }

    private void previewJson() {
        try {
            String json = buildJson();
            previewArea.setText(json.length() > 3000 ? json.substring(0, 3000) + "\n... (truncated)" : json);
        } catch (Exception e) {
            previewArea.setText("Error: " + e.getMessage());
        }
    }

    private void previewCsv() {
        // Collect relevant requests
        List<Request> requests = (targetCollection != null)
                ? collectRequestsForCollection(targetCollection)
                : databaseManager.getAllRequests();
        StringBuilder sb = new StringBuilder("ID,Folder,Method,URL,Created At\n");
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < Math.min(requests.size(), 10); i++) {
            Request r = requests.get(i);
            sb.append(r.getId()).append(",")
              .append(r.getFolderId()).append(",")
              .append(csv(r.getMethod())).append(",")
              .append(csv(r.getUrl())).append(",")
              .append(df.format(new Date(r.getCreatedAt()))).append("\n");
        }
        if (requests.size() > 10) sb.append("... (").append(requests.size() - 10).append(" more)");
        previewArea.setText(sb.toString());
    }

    /** Collect all requests in a collection and its subfolders recursively. */
    private List<Request> collectRequestsForCollection(Folder collection) {
        List<Folder> allFolders = databaseManager.getAllFolders();
        List<Request> result = new java.util.ArrayList<>();
        collectRequestsRecursive(collection, allFolders, result);
        return result;
    }

    private void collectRequestsRecursive(Folder folder, List<Folder> allFolders, List<Request> result) {
        result.addAll(databaseManager.getRequestsByFolder(folder.getId()));
        for (Folder f : allFolders) {
            if (f.getParentId() != null && f.getParentId() == folder.getId()) {
                collectRequestsRecursive(f, allFolders, result);
            }
        }
    }

    // ── Export ────────────────────────────────────────────────────────────

    private void exportToFile() {
        String ext  = jsonRadioButton.isSelected() ? "json" : "csv";
        String desc = jsonRadioButton.isSelected() ? "RequestJar JSON (*.json)" : "CSV Files (*.csv)";
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(desc, ext));
        fc.setSelectedFile(new File("requestjar_export_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "." + ext));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = fc.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith("." + ext))
            file = new File(file.getParent(), file.getName() + "." + ext);

        try {
            if (jsonRadioButton.isSelected()) writeJson(file);
            else writeCsv(file);
            JOptionPane.showMessageDialog(this,
                    "Exported to: " + file.getAbsolutePath(), "Export Successful", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── JSON (full hierarchy) ─────────────────────────────────────────────

    /** Build JSON containing collections → subfolders → requests. */
    private String buildJson() throws Exception {
        List<Folder> folders = databaseManager.getAllFolders();
        List<Request> allRequests = databaseManager.getAllRequests();

        List<Map<String, Object>> collections = new ArrayList<>();
        for (Folder f : folders) {
            if (f.getParentId() != null) continue; // skip subfolders at top level
            // If a specific collection is targeted, skip others
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

        // Subfolders
        List<Map<String, Object>> subs = new ArrayList<>();
        for (Folder f : all) {
            if (f.getParentId() != null && f.getParentId() == folder.getId()) {
                subs.add(buildFolderNode(f, all, allReqs));
            }
        }
        node.put("subfolders", subs);

        // Requests
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
                rm.put("tags", r.getTags());
                rm.put("created_at", r.getCreatedAt());
                reqs.add(rm);
            }
        }
        node.put("requests", reqs);
        return node;
    }

    private void writeJson(File file) throws Exception {
        try (FileWriter w = new FileWriter(file)) {
            w.write(buildJson());
        }
    }

    private void writeCsv(File file) throws IOException {
        List<Request> requests = (targetCollection != null)
                ? collectRequestsForCollection(targetCollection)
                : databaseManager.getAllRequests();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try (FileWriter w = new FileWriter(file)) {
            w.write("ID,Folder ID,Method,URL,Headers,Body,Created At\n");
            for (Request r : requests) {
                w.write(r.getId() + "," + r.getFolderId() + "," +
                        csv(r.getMethod()) + "," + csv(r.getUrl()) + "," +
                        csv(r.getHeaders()) + "," + csv(r.getBody()) + "," +
                        df.format(new Date(r.getCreatedAt())) + "\n");
            }
        }
    }

    private String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
