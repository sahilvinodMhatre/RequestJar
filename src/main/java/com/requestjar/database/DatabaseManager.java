package com.requestjar.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {

    private static final Logger logger = Logger.getLogger(DatabaseManager.class.getName());
    private static final int MAX_FOLDER_NAME_LENGTH = 255;

    private final Connection connection;
    private final Object dbLock = new Object();

    public DatabaseManager() {
        connection = initializeDatabase();
    }

    // ── F-01 fix: deterministic DB path in user home ─────────────────────

    private static String buildDbPath() {
        String home = System.getProperty("user.home");
        Path dir = Path.of(home, ".requestjar");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not create .requestjar directory", e);
        }
        return dir.resolve("requestjar.db").toString();
    }

    private Connection initializeDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            String dbPath = buildDbPath();
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            if (conn != null) {
                createTables(conn);
                migrateSchema(conn);
            }
            return conn;
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "SQLite JDBC driver not found", e);
            return null;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database initialization failed", e);
            return null;
        }
    }

    private void createTables(Connection conn) throws SQLException {
        String createFoldersTable = """
            CREATE TABLE IF NOT EXISTS folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (parent_id) REFERENCES folders (id)
            )
        """;
        String createRequestsTable = """
            CREATE TABLE IF NOT EXISTS requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL,
                method TEXT NOT NULL,
                url TEXT NOT NULL,
                headers TEXT,
                body TEXT,
                full_request TEXT NOT NULL,
                tags TEXT,
                created_at INTEGER NOT NULL,
                host TEXT DEFAULT '',
                port INTEGER DEFAULT 80,
                protocol TEXT DEFAULT 'http',
                response TEXT DEFAULT '',
                FOREIGN KEY (folder_id) REFERENCES folders (id)
            )
        """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createFoldersTable);
            stmt.execute(createRequestsTable);
        }
    }

    private void migrateSchema(Connection conn) {
        String[] migrations = {
            "ALTER TABLE requests ADD COLUMN host TEXT DEFAULT ''",
            "ALTER TABLE requests ADD COLUMN port INTEGER DEFAULT 80",
            "ALTER TABLE requests ADD COLUMN protocol TEXT DEFAULT 'http'",
            "ALTER TABLE requests ADD COLUMN response TEXT DEFAULT ''"
        };
        for (String sql : migrations) {
            try (Statement s = conn.createStatement()) {
                s.execute(sql);
            } catch (SQLException ignored) {
                // Column already exists — safe to ignore
            }
        }
    }

    // ── Input validation ──────────────────────────────────────────────────

    /**
     * Validates and sanitizes a folder name.
     * @return the sanitized name, or null if invalid.
     */
    public static String sanitizeFolderName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_FOLDER_NAME_LENGTH) return null;
        for (char c : trimmed.toCharArray()) {
            if (c != '\t' && c != ' ' && Character.isISOControl(c)) return null;
        }
        return trimmed;
    }

    // ── Folder operations ─────────────────────────────────────────────────

    public List<Folder> getAllFolders() {
        List<Folder> folders = new ArrayList<>();
        String sql = "SELECT * FROM folders ORDER BY parent_id, name";
        synchronized (dbLock) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    folders.add(readFolder(rs));
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error loading folders", e);
            }
        }
        return folders;
    }

    private Folder readFolder(ResultSet rs) throws SQLException {
        Folder folder = new Folder();
        folder.setId(rs.getInt("id"));
        folder.setName(rs.getString("name"));
        int parentId = rs.getInt("parent_id");
        folder.setParentId(rs.wasNull() ? null : parentId);
        folder.setCreatedAt(rs.getLong("created_at"));
        return folder;
    }

    public Folder createFolder(String name, Integer parentId) {
        String sanitized = sanitizeFolderName(name);
        if (sanitized == null) return null;

        String sql = "INSERT INTO folders (name, parent_id, created_at) VALUES (?, ?, ?)";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, sanitized);
                if (parentId != null) {
                    pstmt.setInt(2, parentId);
                } else {
                    pstmt.setNull(2, Types.INTEGER);
                }
                pstmt.setLong(3, System.currentTimeMillis());
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows > 0) {
                    try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            Folder folder = new Folder();
                            folder.setId(generatedKeys.getInt(1));
                            folder.setName(sanitized);
                            folder.setParentId(parentId);
                            folder.setCreatedAt(System.currentTimeMillis());
                            return folder;
                        }
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error creating folder", e);
            }
        }
        return null;
    }

    public boolean renameFolder(int folderId, String newName) {
        String sanitized = sanitizeFolderName(newName);
        if (sanitized == null) return false;

        String sql = "UPDATE folders SET name = ? WHERE id = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, sanitized);
                pstmt.setInt(2, folderId);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error renaming folder", e);
                return false;
            }
        }
    }

    public boolean deleteFolder(int folderId) {
        synchronized (dbLock) {
            try {
                connection.setAutoCommit(false);
                deleteFolderRecursive(folderId);
                connection.commit();
                return true;
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ex) {
                    logger.log(Level.WARNING, "Rollback failed", ex);
                }
                logger.log(Level.WARNING, "Error deleting folder", e);
                return false;
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException e) {
                    logger.log(Level.WARNING, "Failed to restore auto-commit", e);
                }
            }
        }
    }

    /** Must be called inside an active transaction and within dbLock. */
    private void deleteFolderRecursive(int folderId) throws SQLException {
        String findChildren = "SELECT id FROM folders WHERE parent_id = ?";
        List<Integer> childIds = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(findChildren)) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) childIds.add(rs.getInt(1));
            }
        }
        for (int childId : childIds) {
            deleteFolderRecursive(childId);
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM requests WHERE folder_id = ?")) {
            ps.setInt(1, folderId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM folders WHERE id = ?")) {
            ps.setInt(1, folderId);
            ps.executeUpdate();
        }
    }

    // ── Request operations ────────────────────────────────────────────────

    private Request readRequest(ResultSet rs) throws SQLException {
        Request r = new Request();
        r.setId(rs.getInt("id"));
        r.setFolderId(rs.getInt("folder_id"));
        r.setMethod(rs.getString("method"));
        r.setUrl(rs.getString("url"));
        r.setHeaders(rs.getString("headers"));
        r.setBody(rs.getString("body"));
        r.setFullRequest(rs.getString("full_request"));
        r.setTags(rs.getString("tags"));
        r.setCreatedAt(rs.getLong("created_at"));
        String host = rs.getString("host");
        r.setHost(host != null ? host : "");
        int port = rs.getInt("port");
        r.setPort(rs.wasNull() ? 80 : port);
        String protocol = rs.getString("protocol");
        r.setProtocol(protocol != null ? protocol : "http");
        String response = rs.getString("response");
        r.setResponse(response != null ? response : "");
        return r;
    }

    public List<Request> getRequestsByFolder(int folderId) {
        List<Request> requests = new ArrayList<>();
        String sql = "SELECT * FROM requests WHERE folder_id = ? ORDER BY created_at DESC";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, folderId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) requests.add(readRequest(rs));
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error loading requests", e);
            }
        }
        return requests;
    }

    public boolean saveRequest(Request request) {
        String sql = """
            INSERT INTO requests
              (folder_id, method, url, headers, body, full_request, tags, created_at,
               host, port, protocol, response)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, request.getFolderId());
                pstmt.setString(2, request.getMethod());
                pstmt.setString(3, request.getUrl());
                pstmt.setString(4, request.getHeaders());
                pstmt.setString(5, request.getBody());
                pstmt.setString(6, request.getFullRequest());
                pstmt.setString(7, request.getTags());
                pstmt.setLong(8, request.getCreatedAt());
                pstmt.setString(9, request.getHost());
                pstmt.setInt(10, request.getPort());
                pstmt.setString(11, request.getProtocol());
                pstmt.setString(12, request.getResponse());
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error saving request", e);
                return false;
            }
        }
    }

    public boolean deleteRequest(int requestId) {
        String sql = "DELETE FROM requests WHERE id = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, requestId);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error deleting request", e);
                return false;
            }
        }
    }

    public boolean moveRequest(int requestId, int newFolderId) {
        String sql = "UPDATE requests SET folder_id = ? WHERE id = ?";
        synchronized (dbLock) {
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, newFolderId);
                pstmt.setInt(2, requestId);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error moving request", e);
                return false;
            }
        }
    }

    /** Atomically reads the original request and inserts a copy into the target folder. */
    public boolean copyRequest(int requestId, int newFolderId) {
        synchronized (dbLock) {
            Request original = null;
            String selectSql = "SELECT * FROM requests WHERE id = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(selectSql)) {
                pstmt.setInt(1, requestId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) original = readRequest(rs);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error reading request for copy", e);
                return false;
            }
            if (original == null) return false;

            String insertSql = """
                INSERT INTO requests
                  (folder_id, method, url, headers, body, full_request, tags, created_at,
                   host, port, protocol, response)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement pstmt = connection.prepareStatement(insertSql)) {
                pstmt.setInt(1, newFolderId);
                pstmt.setString(2, original.getMethod());
                pstmt.setString(3, original.getUrl());
                pstmt.setString(4, original.getHeaders());
                pstmt.setString(5, original.getBody());
                pstmt.setString(6, original.getFullRequest());
                pstmt.setString(7, original.getTags());
                pstmt.setLong(8, System.currentTimeMillis());
                pstmt.setString(9, original.getHost());
                pstmt.setInt(10, original.getPort());
                pstmt.setString(11, original.getProtocol());
                pstmt.setString(12, original.getResponse());
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error copying request", e);
                return false;
            }
        }
    }

    public List<Request> getAllRequests() {
        List<Request> requests = new ArrayList<>();
        String sql = "SELECT * FROM requests ORDER BY created_at DESC";
        synchronized (dbLock) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) requests.add(readRequest(rs));
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error loading all requests", e);
            }
        }
        return requests;
    }

    public void close() {
        synchronized (dbLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error closing database", e);
            }
        }
    }
}
