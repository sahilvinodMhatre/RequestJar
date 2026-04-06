package com.requestjar;

import burp.*;
import com.requestjar.database.DatabaseManager;
import com.requestjar.database.Request;
import com.requestjar.database.Folder;
import com.requestjar.gui.RequestJarTab;
import com.requestjar.gui.FolderSelectionDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

public class RequestJarExtension implements IBurpExtender, ITab, IContextMenuFactory {
    
    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    private DatabaseManager databaseManager;
    private RequestJarTab requestJarTab;
    
    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();
        
        // Set extension name
        callbacks.setExtensionName("RequestJar");
        
        // Initialize database
        databaseManager = new DatabaseManager();
        
        // Create main tab
        requestJarTab = new RequestJarTab(callbacks, databaseManager);
        
        // Add custom tab to Burp Suite
        callbacks.addSuiteTab(this);
        
        // Register context menu factory
        callbacks.registerContextMenuFactory(this);
        
        // Print extension loaded message
        callbacks.printOutput("RequestJar extension loaded successfully!");
    }
    
    @Override
    public String getTabCaption() {
        return "RequestJar";
    }
    
    @Override
    public Component getUiComponent() {
        return requestJarTab;
    }
    
    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        List<JMenuItem> menuItems = new ArrayList<>();
        
        // Check if the invocation is for a request/response
        if (invocation.getSelectedMessages() != null && invocation.getSelectedMessages().length > 0) {
            JMenuItem sendToRequestJarItem = new JMenuItem("Send to RequestJar");
            sendToRequestJarItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    IHttpRequestResponse[] messages = invocation.getSelectedMessages();
                    for (IHttpRequestResponse message : messages) {
                        showSaveDialog(message);
                    }
                }
            });
            menuItems.add(sendToRequestJarItem);
        }
        
        return menuItems;
    }
    
    private void showSaveDialog(IHttpRequestResponse messageInfo) {
        // Show folder selection dialog
        FolderSelectionDialog dialog = new FolderSelectionDialog(databaseManager);
        dialog.setVisible(true);
        
        if (dialog.getSelectedFolder() != null) {
            // Save request to selected folder
            Request request = createRequestFromMessage(messageInfo, dialog.getSelectedFolder().getId());
            databaseManager.saveRequest(request);
            
            // Refresh the UI
            requestJarTab.refreshRequestTree();
            
            callbacks.printOutput("Request saved to RequestJar: " + request.getUrl());
        }
    }
    
    private Request createRequestFromMessage(IHttpRequestResponse messageInfo, int folderId) {
        byte[] requestBytes = messageInfo.getRequest();
        String requestStr = helpers.bytesToString(requestBytes);

        // ── Pull host / port / protocol straight from Burp's service object ──
        // This is the authoritative source — no guessing from headers.
        burp.IHttpService service = messageInfo.getHttpService();
        String host     = service != null ? service.getHost()     : "";
        int    port     = service != null ? service.getPort()     : 80;
        String protocol = service != null ? service.getProtocol() : "http"; // "http" or "https"

        // ── Parse first line: METHOD /path HTTP/1.x ──────────────────────
        int firstLineEnd = requestStr.indexOf("\r\n");
        if (firstLineEnd == -1) firstLineEnd = requestStr.indexOf("\n");
        String firstLine = firstLineEnd > 0 ? requestStr.substring(0, firstLineEnd).trim() : "";
        String[] parts  = firstLine.split(" ");
        String method   = parts.length > 0 ? parts[0] : "GET";
        String path     = parts.length > 1 ? parts[1] : "/";

        // Build a full URL so it's useful when displayed in the table
        String fullUrl = protocol + "://" + host + path;

        // ── Split headers / body ──────────────────────────────────────────
        int headersEnd = requestStr.indexOf("\r\n\r\n");
        int bodyOffset = 4;
        if (headersEnd == -1) { headersEnd = requestStr.indexOf("\n\n"); bodyOffset = 2; }

        String headers = headersEnd > 0 ? requestStr.substring(0, headersEnd) : requestStr;
        String body    = headersEnd > 0 ? requestStr.substring(headersEnd + bodyOffset) : "";

        Request requestObj = new Request();
        requestObj.setFolderId(folderId);
        requestObj.setMethod(method);
        requestObj.setUrl(fullUrl);
        requestObj.setHeaders(headers);
        requestObj.setBody(body);
        requestObj.setFullRequest(requestStr);
        requestObj.setHost(host);
        requestObj.setPort(port);
        requestObj.setProtocol(protocol);
        requestObj.setCreatedAt(System.currentTimeMillis());

        return requestObj;
    }
}
