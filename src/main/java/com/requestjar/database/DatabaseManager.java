package com.requestjar.database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private Connection connection;
    private static final String DB_URL = "jdbc:sqlite:requestjar.db";
    
    public DatabaseManager() {
        initializeDatabase();
    }
    
    private void initializeDatabase() {
        try {
            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");
            System.out.println("SQLite driver loaded successfully");
            
            // Create database connection
            connection = DriverManager.getConnection(DB_URL);
            System.out.println("Database connection established: " + DB_URL);
            
            if (connection != null) {
                createTables();
                System.out.println("Database tables created successfully");
            } else {
                System.err.println("Failed to establish database connection!");
            }
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found: " + e.getMessage());
            e.printStackTrace();
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void createTables() throws SQLException {
        // Create folders table
        String createFoldersTable = """
            CREATE TABLE IF NOT EXISTS folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (parent_id) REFERENCES folders (id)
            )
        """;

        // Create requests table (includes service info for correct Burp API calls)
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
                FOREIGN KEY (folder_id) REFERENCES folders (id)
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createFoldersTable);
            stmt.execute(createRequestsTable);
            // No default root folder — users create their own collections
        }

        // Migrate existing databases that lack the new service-info columns
        migrateSchema();
    }

    /**
     * Safely adds host/port/protocol columns to existing databases.
     * SQLite ignores the ALTER TABLE if the column already exists
     * via the try-catch — this is safe to call on every startup.
     */
    private void migrateSchema() {
        String[][] migrations = {
            {"ALTER TABLE requests ADD COLUMN host TEXT DEFAULT ''"},
            {"ALTER TABLE requests ADD COLUMN port INTEGER DEFAULT 80"},
            {"ALTER TABLE requests ADD COLUMN protocol TEXT DEFAULT 'http'"}
        };
        for (String[] m : migrations) {
            try (Statement s = connection.createStatement()) {
                s.execute(m[0]);
            } catch (SQLException ignored) {
                // Column already exists — that's fine
            }
        }
    }

    // Folder operations
    public List<Folder> getAllFolders() {
        List<Folder> folders = new ArrayList<>();
        String sql = "SELECT * FROM folders ORDER BY parent_id, name";
        System.out.println("Loading all folders...");
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Folder folder = new Folder();
                folder.setId(rs.getInt("id"));
                folder.setName(rs.getString("name"));
                int parentId = rs.getInt("parent_id");
                folder.setParentId(rs.wasNull() ? null : parentId);
                folder.setCreatedAt(rs.getLong("created_at"));
                folders.add(folder);
                System.out.println("Loaded folder: " + folder.getName() + " (ID: " + folder.getId() + ", Parent: " + folder.getParentId() + ")");
            }
        } catch (SQLException e) {
            System.err.println("Error loading folders: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("Total folders loaded: " + folders.size());
        return folders;
    }
    
    public Folder createFolder(String name, Integer parentId) {
        String sql = "INSERT INTO folders (name, parent_id, created_at) VALUES (?, ?, ?)";
        System.out.println("Creating folder: " + name + " with parent: " + parentId);
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, name);
            if (parentId != null) {
                pstmt.setInt(2, parentId);
            } else {
                pstmt.setNull(2, Types.INTEGER);
            }
            pstmt.setLong(3, System.currentTimeMillis());
            
            int affectedRows = pstmt.executeUpdate();
            System.out.println("Affected rows: " + affectedRows);
            
            if (affectedRows > 0) {
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        Folder folder = new Folder();
                        folder.setId(generatedKeys.getInt(1));
                        folder.setName(name);
                        folder.setParentId(parentId);
                        folder.setCreatedAt(System.currentTimeMillis());
                        System.out.println("Folder created with ID: " + folder.getId());
                        return folder;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating folder: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    public boolean deleteFolder(int folderId) {
        try {
            connection.setAutoCommit(false);
            deleteFolderRecursive(folderId);
            connection.commit();
            return true;
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            return false;
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    /**
     * Recursively deletes all subfolders (and their requests) first,
     * then deletes the requests and the folder itself.
     * Must be called inside an active transaction.
     */
    private void deleteFolderRecursive(int folderId) throws SQLException {
        // 1. Find all direct children
        String findChildren = "SELECT id FROM folders WHERE parent_id = ?";
        List<Integer> childIds = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(findChildren)) {
            ps.setInt(1, folderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) childIds.add(rs.getInt(1));
            }
        }

        // 2. Recurse into each child first
        for (int childId : childIds) {
            deleteFolderRecursive(childId);
        }

        // 3. Delete requests in this folder
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM requests WHERE folder_id = ?")) {
            ps.setInt(1, folderId);
            ps.executeUpdate();
        }

        // 4. Delete the folder itself
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
        // Service info (may be empty for requests saved before this version)
        String host = rs.getString("host");
        r.setHost(host != null ? host : "");
        int port = rs.getInt("port");
        r.setPort(rs.wasNull() ? 80 : port);
        String protocol = rs.getString("protocol");
        r.setProtocol(protocol != null ? protocol : "http");
        return r;
    }

    public List<Request> getRequestsByFolder(int folderId) {
        List<Request> requests = new ArrayList<>();
        String sql = "SELECT * FROM requests WHERE folder_id = ? ORDER BY created_at DESC";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, folderId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) requests.add(readRequest(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return requests;
    }

    public boolean saveRequest(Request request) {
        String sql = """
            INSERT INTO requests
              (folder_id, method, url, headers, body, full_request, tags, created_at, host, port, protocol)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, request.getFolderId());
            pstmt.setString(2, request.getMethod());
            pstmt.setString(3, request.getUrl());
            pstmt.setString(4, request.getHeaders());
            pstmt.setString(5, request.getBody());
            pstmt.setString(6, request.getFullRequest());
            pstmt.setString(7, request.getTags());
            pstmt.setLong(8, request.getCreatedAt());
            pstmt.setString(9,  request.getHost());
            pstmt.setInt(10,    request.getPort());
            pstmt.setString(11, request.getProtocol());
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean deleteRequest(int requestId) {
        String sql = "DELETE FROM requests WHERE id = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, requestId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public List<Request> getAllRequests() {
        List<Request> requests = new ArrayList<>();
        String sql = "SELECT * FROM requests ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) requests.add(readRequest(rs));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return requests;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
