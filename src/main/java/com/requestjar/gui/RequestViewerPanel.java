package com.requestjar.gui;

import burp.IBurpExtenderCallbacks;
import burp.IExtensionHelpers;
import com.requestjar.database.DatabaseManager;
import com.requestjar.database.Folder;
import com.requestjar.database.Request;
import com.requestjar.utils.BurpIntegration;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

public class RequestViewerPanel extends JPanel {

    private final IBurpExtenderCallbacks callbacks;
    private final IExtensionHelpers helpers;
    private final DatabaseManager databaseManager;

    private JTable requestTable;
    private DefaultTableModel tableModel;
    private JTextPane requestDetailsPane;
    private Folder currentFolder;
    private List<Request> currentRequests;

    // ── Syntax highlight colours (dark-terminal palette) ──────────────────
    private static final Color BG_COLOR        = new Color(0x1e, 0x1e, 0x2e);
    private static final Color METHOD_COLOR    = new Color(0xff, 0x9e, 0x64); // orange
    private static final Color PATH_COLOR      = new Color(0x89, 0xdc, 0xeb); // cyan
    private static final Color VERSION_COLOR   = new Color(0xa9, 0xb1, 0xd6); // grey
    private static final Color HEADER_NAME_COLOR  = new Color(0x7d, 0xce, 0xa3); // teal-green
    private static final Color COLON_COLOR     = new Color(0xa9, 0xb1, 0xd6);
    private static final Color HEADER_VAL_COLOR   = new Color(0xc0, 0xca, 0xf5); // lavender
    private static final Color BODY_COLOR      = new Color(0xe0, 0xde, 0x86); // yellow-green
    private static final Color SEPARATOR_COLOR = new Color(0x56, 0x5f, 0x89); // dim purple

    public RequestViewerPanel(IBurpExtenderCallbacks callbacks, DatabaseManager databaseManager) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();
        this.databaseManager = databaseManager;
        initializeUI();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(5, 5, 5, 5));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                createRequestListPanel(), createRequestDetailsPanel());
        splitPane.setDividerLocation(280);
        add(splitPane, BorderLayout.CENTER);
    }

    // ── Request List Panel ────────────────────────────────────────────────

    private JPanel createRequestListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Requests"));

        String[] columns = {"Method", "URL", "Saved At"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        requestTable = new JTable(tableModel);
        requestTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestTable.getTableHeader().setReorderingAllowed(false);

        requestTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = requestTable.rowAtPoint(e.getPoint());
                    if (row >= 0) requestTable.setRowSelectionInterval(row, row);
                    showRequestContextMenu(e);
                }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showRequestDetails();
            }
        });
        requestTable.getSelectionModel().addListSelectionListener(
                e -> { if (!e.getValueIsAdjusting()) showRequestDetails(); });

        panel.add(new JScrollPane(requestTable), BorderLayout.CENTER);
        panel.add(createButtonPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));

        panel.add(makeButton("Send to Repeater",  e -> sendToRepeater()));
        panel.add(makeButton("Send to Intruder",  e -> sendToIntruder()));
        panel.add(makeButton("Send to Comparer",  e -> sendToComparer()));
        panel.add(makeButton("Send to Scanner",   e -> sendToScanner()));
        panel.add(Box.createHorizontalStrut(10));
        panel.add(makeButton("Delete Request",    e -> deleteSelectedRequest()));
        return panel;
    }

    private JButton makeButton(String label, ActionListener al) {
        JButton b = new JButton(label);
        b.addActionListener(al);
        return b;
    }

    // ── Request Details Panel (syntax-highlighted) ─────────────────────────

    private JPanel createRequestDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Request Details"));

        requestDetailsPane = new JTextPane();
        requestDetailsPane.setEditable(false);
        requestDetailsPane.setBackground(BG_COLOR);
        requestDetailsPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        requestDetailsPane.setCaretColor(Color.WHITE);

        JScrollPane scrollPane = new JScrollPane(requestDetailsPane);
        scrollPane.getViewport().setBackground(BG_COLOR);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    // ── HTTP Syntax Highlighting ──────────────────────────────────────────

    private void applyHttpSyntaxHighlighting(String raw) {
        StyledDocument doc = requestDetailsPane.getStyledDocument();
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}

        if (raw == null || raw.isEmpty()) return;

        String[] lines = raw.split("\\r?\\n", -1);
        boolean inHeaders = true;
        boolean firstLine = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String nl = (i < lines.length - 1) ? "\n" : "";

            if (firstLine) {
                // "METHOD /path HTTP/1.1"
                String[] parts = line.split(" ", 3);
                appendStyled(doc, parts.length > 0 ? parts[0] : "", METHOD_COLOR, true);
                if (parts.length > 1) {
                    appendStyled(doc, " " + parts[1], PATH_COLOR, false);
                }
                if (parts.length > 2) {
                    appendStyled(doc, " " + parts[2], VERSION_COLOR, false);
                }
                appendStyled(doc, nl, VERSION_COLOR, false);
                firstLine = false;

            } else if (inHeaders) {
                if (line.trim().isEmpty()) {
                    // blank separator line
                    appendStyled(doc, nl, SEPARATOR_COLOR, false);
                    inHeaders = false;
                } else {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        appendStyled(doc, line.substring(0, colon), HEADER_NAME_COLOR, false);
                        appendStyled(doc, ":", COLON_COLOR, false);
                        appendStyled(doc, line.substring(colon + 1) + nl, HEADER_VAL_COLOR, false);
                    } else {
                        appendStyled(doc, line + nl, HEADER_VAL_COLOR, false);
                    }
                }
            } else {
                // body
                appendStyled(doc, line + nl, BODY_COLOR, false);
            }
        }

        requestDetailsPane.setCaretPosition(0);
    }

    private void appendStyled(StyledDocument doc, String text, Color color, boolean bold) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setBold(attrs, bold);
        try { doc.insertString(doc.getLength(), text, attrs); }
        catch (BadLocationException ignored) {}
    }

    // ── Display / Selection Logic ─────────────────────────────────────────

    public void displayRequests(List<Request> requests, Folder folder) {
        this.currentFolder = folder;
        this.currentRequests = requests;
        tableModel.setRowCount(0);
        for (Request req : requests) {
            tableModel.addRow(new Object[]{
                req.getMethod(), req.getUrl(),
                new java.util.Date(req.getCreatedAt()).toString()
            });
        }
        applyHttpSyntaxHighlighting("");
    }

    private void showRequestDetails() {
        Request req = getSelectedRequest();
        if (req != null) applyHttpSyntaxHighlighting(req.getFullRequest());
    }

    private Request getSelectedRequest() {
        int row = requestTable.getSelectedRow();
        if (row >= 0 && currentFolder != null) {
            if (currentRequests == null || row >= currentRequests.size()) {
                currentRequests = databaseManager.getRequestsByFolder(currentFolder.getId());
            }
            if (row < currentRequests.size()) return currentRequests.get(row);
        }
        return null;
    }

    // ── Context Menu ──────────────────────────────────────────────────────

    private void showRequestContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Send to Repeater",  ev -> sendToRepeater()));
        menu.add(menuItem("Send to Intruder",  ev -> sendToIntruder()));
        menu.add(menuItem("Send to Comparer",  ev -> sendToComparer()));
        menu.add(menuItem("Send to Scanner",   ev -> sendToScanner()));
        menu.addSeparator();
        menu.add(menuItem("Delete Request",    ev -> deleteSelectedRequest()));
        menu.show(requestTable, e.getX(), e.getY());
    }

    private JMenuItem menuItem(String label, ActionListener al) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(al);
        return item;
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private void deleteSelectedRequest() {
        Request req = getSelectedRequest();
        if (req == null) return;
        int ans = JOptionPane.showConfirmDialog(this,
                "Delete this request?", "Delete Request",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans == JOptionPane.YES_OPTION && databaseManager.deleteRequest(req.getId())) {
            currentRequests = databaseManager.getRequestsByFolder(currentFolder.getId());
            displayRequests(currentRequests, currentFolder);
        }
    }

    private void sendToRepeater()  { Request r = getSelectedRequest(); if (r != null) BurpIntegration.sendToRepeater(callbacks, helpers, r); }
    private void sendToIntruder()  { Request r = getSelectedRequest(); if (r != null) BurpIntegration.sendToIntruder(callbacks, helpers, r); }
    private void sendToComparer()  { Request r = getSelectedRequest(); if (r != null) BurpIntegration.sendToComparer(callbacks, helpers, r); }
    private void sendToScanner()   { Request r = getSelectedRequest(); if (r != null) BurpIntegration.sendToScanner(callbacks, helpers, r); }
}
