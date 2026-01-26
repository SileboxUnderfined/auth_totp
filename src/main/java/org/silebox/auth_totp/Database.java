package org.silebox.auth_totp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Optional;

public class Database {
    private String jdbc_url;
    private static final int ERROR_CODE = -5000;

    Database(Path db_path) {
        try {
            Files.createDirectories(db_path);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String dbfile_path = db_path.resolve("users.db").toString();
        jdbc_url = "jdbc:sqlite:" + dbfile_path;


        try (Connection conn = DriverManager.getConnection(jdbc_url)) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, secret TEXT)");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void AddUser(String uuid, String secret) {
        try (Connection conn = DriverManager.getConnection(jdbc_url)) {
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (id, secret) VALUES (?, ?)")) {
                stmt.setString(1, uuid);
                stmt.setString(2, secret);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Optional<String> GetSecretKey(String uuid) {
        try (Connection conn = DriverManager.getConnection(jdbc_url)) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT secret FROM users WHERE id = ?")) {
                stmt.setString(1, uuid);

                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    return Optional.ofNullable(rs.getString("secret"));
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return Optional.empty();
    }

    public boolean IsUserRegistered(String uuid) {
        try (Connection conn = DriverManager.getConnection(jdbc_url)) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT EXISTS(SELECT * FROM users WHERE id = ?)")) {
                stmt.setString(1, uuid);

                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) == 1;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
}
