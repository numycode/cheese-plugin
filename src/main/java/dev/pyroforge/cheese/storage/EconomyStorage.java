package dev.pyroforge.cheese.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Single-writer SQLite storage for the Cheese economy's current/max supply. All access is
 * serialized through one JDBC connection guarded by a lock, since the async full scan and
 * command handlers can otherwise write concurrently (see docs/SPEC.md "SQLite" section).
 */
public final class EconomyStorage implements AutoCloseable {

    private final Object lock = new Object();
    private final Connection connection;

    public EconomyStorage(File databaseFile) throws SQLException {
        File parent = databaseFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        initSchema();
    }

    private void initSchema() throws SQLException {
        synchronized (lock) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS economy_state (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            current_supply INTEGER NOT NULL,
                            max_supply INTEGER NOT NULL,
                            seeded INTEGER NOT NULL
                        )
                        """);
                statement.execute(
                        "INSERT OR IGNORE INTO economy_state (id, current_supply, max_supply, seeded) "
                                + "VALUES (1, 0, 0, 0)");
            }
        }
    }

    public boolean isSeeded() throws SQLException {
        return readLong("SELECT seeded FROM economy_state WHERE id = 1") != 0;
    }

    public long getCurrentSupply() throws SQLException {
        return readLong("SELECT current_supply FROM economy_state WHERE id = 1");
    }

    public long getMaxSupply() throws SQLException {
        return readLong("SELECT max_supply FROM economy_state WHERE id = 1");
    }

    /** Sets currentSupply/maxSupply and marks the economy as seeded, in one transaction. */
    public void seedInitialState(long currentSupplyUnits, long maxSupplyUnits) throws SQLException {
        synchronized (lock) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE economy_state SET current_supply = ?, max_supply = ?, seeded = 1 WHERE id = 1")) {
                statement.setLong(1, currentSupplyUnits);
                statement.setLong(2, maxSupplyUnits);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void setMaxSupply(long maxSupplyUnits) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE economy_state SET max_supply = ? WHERE id = 1")) {
                statement.setLong(1, maxSupplyUnits);
                statement.executeUpdate();
            }
        }
    }

    /**
     * Mints new gold into circulation: raises currentSupply and maxSupply by the same amount,
     * in one transaction (see docs/SPEC.md "/cheese add" — minting Cheese also raises the cap
     * by the same amount, so the used/allowed ratio stays meaningful).
     */
    public void mint(long units) throws SQLException {
        adjustBothBy(units);
    }

    /** Destroys gold out of circulation: lowers currentSupply and maxSupply by the same amount. */
    public void destroy(long units) throws SQLException {
        adjustBothBy(-units);
    }

    private void adjustBothBy(long deltaUnits) throws SQLException {
        synchronized (lock) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE economy_state SET current_supply = current_supply + ?, max_supply = max_supply + ? "
                            + "WHERE id = 1")) {
                statement.setLong(1, deltaUnits);
                statement.setLong(2, deltaUnits);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private long readLong(String sql) throws SQLException {
        synchronized (lock) {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock) {
            connection.close();
        }
    }
}
