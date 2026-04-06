package com.requestjar.utils;

import burp.IBurpExtenderCallbacks;
import burp.IExtensionHelpers;
import com.requestjar.database.Request;

public class BurpIntegration {

    /**
     * Fallback: if a request was saved before the service-info fix,
     * host/port/protocol fields will be empty defaults.
     * In that case we try to parse the Host header as a best-effort.
     */
    private static String resolveHost(Request request) {
        String h = request.getHost();
        if (h != null && !h.isBlank()) return h;
        // Fallback: parse Host header
        for (String line : request.getFullRequest().split("\\r?\\n")) {
            if (line.trim().isEmpty()) break;
            if (line.toLowerCase().startsWith("host:")) {
                String val = line.substring(5).trim();
                return val.contains(":") ? val.split(":")[0].trim() : val;
            }
        }
        return "localhost";
    }

    private static int resolvePort(Request request) {
        int p = request.getPort();
        if (p > 0) return p;
        return request.isHttps() ? 443 : 80;
    }

    private static boolean resolveHttps(Request request) {
        // Prefer the stored protocol field
        if (request.getProtocol() != null && !request.getProtocol().isBlank()) {
            return request.isHttps();
        }
        // Fallback: guess from port
        return request.getPort() == 443;
    }

    public static void sendToRepeater(IBurpExtenderCallbacks callbacks, IExtensionHelpers helpers, Request request) {
        try {
            String  host  = resolveHost(request);
            int     port  = resolvePort(request);
            boolean https = resolveHttps(request);
            byte[]  bytes = helpers.stringToBytes(request.getFullRequest());
            callbacks.sendToRepeater(host, port, https, bytes, "RequestJar");
            callbacks.printOutput("RequestJar: Sent to Repeater → " + (https ? "https" : "http") + "://" + host + ":" + port);
        } catch (Exception e) {
            callbacks.printError("RequestJar: Failed to send to Repeater - " + e.getMessage());
        }
    }

    public static void sendToIntruder(IBurpExtenderCallbacks callbacks, IExtensionHelpers helpers, Request request) {
        try {
            String  host  = resolveHost(request);
            int     port  = resolvePort(request);
            boolean https = resolveHttps(request);
            byte[]  bytes = helpers.stringToBytes(request.getFullRequest());
            callbacks.sendToIntruder(host, port, https, bytes);
            callbacks.printOutput("RequestJar: Sent to Intruder → " + host);
        } catch (Exception e) {
            callbacks.printError("RequestJar: Failed to send to Intruder - " + e.getMessage());
        }
    }

    public static void sendToComparer(IBurpExtenderCallbacks callbacks, IExtensionHelpers helpers, Request request) {
        try {
            callbacks.sendToComparer(helpers.stringToBytes(request.getFullRequest()));
            callbacks.printOutput("RequestJar: Sent to Comparer");
        } catch (Exception e) {
            callbacks.printError("RequestJar: Failed to send to Comparer - " + e.getMessage());
        }
    }

    /** Scanner is only available in Burp Suite Pro. */
    public static void sendToScanner(IBurpExtenderCallbacks callbacks, IExtensionHelpers helpers, Request request) {
        try {
            String  host  = resolveHost(request);
            int     port  = resolvePort(request);
            boolean https = resolveHttps(request);
            byte[]  bytes = helpers.stringToBytes(request.getFullRequest());
            callbacks.doActiveScan(host, port, https, bytes);
            callbacks.printOutput("RequestJar: Sent to Scanner → " + host);
        } catch (Exception e) {
            callbacks.printError("RequestJar: Scanner not available - " + e.getMessage());
        }
    }
}
