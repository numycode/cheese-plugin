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

    /**
     * @throws IllegalArgumentException if {@code maxSupplyUnits} is negative — a negative cap has
     *     no valid interpretation under any admin intent. Deliberately does NOT reject a cap set
     *     below currentSupply: an admin intentionally tightening the cap without physically
     *     clawing back gold immediately (freezing new minting/creation until natural destruction
     *     events bring currentSupply back under it) is a legitimate use of this command, distinct
     *     from {@code /cheese remove} which lowers both together.
     */
    public void setMaxSupply(long maxSupplyUnits) throws SQLException {
        if (maxSupplyUnits < 0) {
            throw new IllegalArgumentException("maxSupplyUnits must not be negative: " + maxSupplyUnits);
        }
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

    /**
     * All-or-nothing admission for gold that can't be partially created (e.g. one furnace smelt
     * yields exactly one ingot — there's no such thing as 4/9ths of one). Returns whether the
     * full amount fit under {@code maxSupply}; if not, nothing is persisted and the caller should
     * cancel the event that would have created it.
     */
    public boolean tryIncreaseCurrentSupplyExact(long units) throws SQLException {
        synchronized (lock) {
            if (getCurrentSupply() + units > getMaxSupply()) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE economy_state SET current_supply = current_supply + ? WHERE id = 1")) {
                statement.setLong(1, units);
                statement.executeUpdate();
            }
            return true;
        }
    }

    /**
     * Partial admission for gold that arrives as a batch of separate stacks (mob drops, natural
     * loot), where trimming to whatever headroom remains is more graceful than voiding the whole
     * batch. Returns the amount actually admitted, which may be less than requested (or 0).
     */
    public long tryIncreaseCurrentSupply(long requestedUnits) throws SQLException {
        synchronized (lock) {
            long headroom = Math.max(0, getMaxSupply() - getCurrentSupply());
            long grantable = Math.min(requestedUnits, headroom);
            if (grantable > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE economy_state SET current_supply = current_supply + ? WHERE id = 1")) {
                    statement.setLong(1, grantable);
                    statement.executeUpdate();
                }
            }
            return grantable;
        }
    }

    /** Removes gold from circulation without touching the cap (survival destruction events). */
    public void decreaseCurrentSupply(long units) throws SQLException {
        synchronized (lock) {
            long newSupply = Math.max(0, getCurrentSupply() - units);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE economy_state SET current_supply = ? WHERE id = 1")) {
                statement.setLong(1, newSupply);
                statement.executeUpdate();
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
