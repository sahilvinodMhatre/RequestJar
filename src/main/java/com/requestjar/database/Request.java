package com.requestjar.database;

public class Request {
    private int id;
    private int folderId;
    private String method;
    private String url;
    private String headers;
    private String body;
    private String fullRequest;
    private String tags;
    private long createdAt;

    // ── Service info — needed for correct Burp API calls ─────────────────
    /** The hostname / IP of the target server (e.g. "api.facebook.com"). */
    private String host   = "";
    /** The TCP port of the target server (e.g. 443). */
    private int    port   = 80;
    /** "http" or "https". */
    private String protocol = "http";

    public Request() {}

    // ── Standard getters/setters ──────────────────────────────────────────

    public int getId()                  { return id; }
    public void setId(int id)           { this.id = id; }

    public int getFolderId()            { return folderId; }
    public void setFolderId(int v)      { this.folderId = v; }

    public String getMethod()           { return method; }
    public void setMethod(String v)     { this.method = v; }

    public String getUrl()              { return url; }
    public void setUrl(String v)        { this.url = v; }

    public String getHeaders()          { return headers; }
    public void setHeaders(String v)    { this.headers = v; }

    public String getBody()             { return body; }
    public void setBody(String v)       { this.body = v; }

    public String getFullRequest()      { return fullRequest; }
    public void setFullRequest(String v){ this.fullRequest = v; }

    public String getTags()             { return tags; }
    public void setTags(String v)       { this.tags = v; }

    public long getCreatedAt()          { return createdAt; }
    public void setCreatedAt(long v)    { this.createdAt = v; }

    // ── Service info getters/setters ──────────────────────────────────────

    public String getHost()             { return host == null ? "" : host; }
    public void setHost(String v)       { this.host = v == null ? "" : v; }

    public int getPort()                { return port; }
    public void setPort(int v)          { this.port = v; }

    public String getProtocol()         { return protocol == null ? "http" : protocol; }
    public void setProtocol(String v)   { this.protocol = v == null ? "http" : v; }

    /** Convenience: true when protocol is "https". */
    public boolean isHttps()            { return "https".equalsIgnoreCase(protocol); }

    @Override
    public String toString() { return method + " " + url; }
}
