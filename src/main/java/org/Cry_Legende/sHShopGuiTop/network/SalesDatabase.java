package org.Cry_Legende.sHShopGuiTop.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ventes de la boutique en MariaDB, partagées par tous les serveurs. Chargée seulement en mode réseau (HikariCP et
 * le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 */
public final class SalesDatabase implements SalesStore {

    private static final String TABLE = "shshopguitop_sales";
    /** Longueur de la colonne item : un nom plus long est coupé plutôt que de faire échouer toute l'écriture. */
    private static final int ITEM_LENGTH = 190;

    private final HikariDataSource pool;

    public SalesDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHShopGuiTop");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : les ventes restent en attente et partent à son retour
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    @Override
    public void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "item VARCHAR(" + ITEM_LENGTH + ") NOT NULL, "
                + "minecraft_id VARCHAR(100) NOT NULL DEFAULT '', "
                + "sold BIGINT NOT NULL DEFAULT 0, "
                + "revenue DOUBLE NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (item)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    @Override
    public boolean isEmpty() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
            return !rs.next();
        }
    }

    @Override
    public void add(Map<String, SalesBuffer.Delta> deltas) throws SQLException {
        if (deltas.isEmpty()) {
            return;
        }
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (item, minecraft_id, sold, revenue) VALUES (?, ?, ?, ?) "
                 + "ON DUPLICATE KEY UPDATE sold = sold + VALUES(sold), revenue = revenue + VALUES(revenue)")) {
            for (Map.Entry<String, SalesBuffer.Delta> entry : deltas.entrySet()) {
                ps.setString(1, cut(entry.getKey(), ITEM_LENGTH));
                ps.setString(2, cut(entry.getValue().minecraftId(), 100));
                ps.setLong(3, entry.getValue().sold());
                ps.setDouble(4, entry.getValue().revenue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public void importMissing(List<Row> rows) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (item, minecraft_id, sold, revenue) VALUES (?, ?, ?, ?)")) {
            for (Row row : rows) {
                ps.setString(1, cut(row.item(), ITEM_LENGTH));
                ps.setString(2, cut(row.minecraftId(), 100));
                ps.setLong(3, row.sold());
                ps.setDouble(4, row.revenue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public List<Row> top(int limit) throws SQLException {
        List<Row> rows = new ArrayList<>(limit);
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT item, minecraft_id, sold, revenue FROM " + TABLE
                 + " ORDER BY sold DESC, item ASC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getDouble(4)));
                }
            }
        }
        return rows;
    }

    @Override
    public void close() {
        pool.close();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
