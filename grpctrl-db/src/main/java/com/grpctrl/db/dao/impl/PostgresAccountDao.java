package com.grpctrl.db.dao.impl;

import com.grpctrl.common.model.Account;
import com.grpctrl.common.model.ServiceLevel;
import com.grpctrl.common.util.CloseableBiConsumer;
import com.grpctrl.db.DataSourceSupplier;
import com.grpctrl.db.dao.AccountDao;
import com.grpctrl.db.dao.ServiceLevelDao;
import com.grpctrl.db.dao.supplier.ServiceLevelDaoSupplier;
import com.grpctrl.db.error.ErrorTransformer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.sql.DataSource;

/**
 * Provides an implementation of an {@link AccountDao} using a JDBC {@link DataSourceSupplier} to communicate
 * with a back-end PostgreSQL database.
 */
public class PostgresAccountDao implements AccountDao {
    @Nonnull
    private final DataSourceSupplier dataSourceSupplier;
    @Nonnull
    private final ServiceLevelDaoSupplier serviceLevelDaoSupplier;

    /**
     * @param dataSourceSupplier the supplier of the JDBC {@link DataSource} to use when communicating with the
     *     back-end database
     * @param serviceLevelDaoSupplier the {@link ServiceLevelDaoSupplier} used to manage service level objects
     */
    public PostgresAccountDao(
            @Nonnull final DataSourceSupplier dataSourceSupplier,
            @Nonnull final ServiceLevelDaoSupplier serviceLevelDaoSupplier) {
        this.dataSourceSupplier = Objects.requireNonNull(dataSourceSupplier);
        this.serviceLevelDaoSupplier = Objects.requireNonNull(serviceLevelDaoSupplier);
    }

    @Nonnull
    private DataSourceSupplier getDataSourceSupplier() {
        return this.dataSourceSupplier;
    }

    @Nonnull
    private ServiceLevelDaoSupplier getServiceLevelDaoSupplier() {
        return this.serviceLevelDaoSupplier;
    }

    @Override
    public void get(@Nonnull final Long accountId, @Nonnull final Consumer<Account> consumer) {
        // NOTE: We don't call the other get method here since it requires the creation of a new collection.
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(consumer);

        final String sql =
                "SELECT a.account_id, a.name, s.max_groups, s.max_tags, s.max_depth FROM accounts a LEFT JOIN "
                        + "service_levels s ON (a.account_id = s.account_id) WHERE a.account_id = ?";

        final DataSource dataSource = getDataSourceSupplier().get();
        try (final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);

            final Account account = new Account();

            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    account.setId(rs.getLong("account_id"));
                    account.setName(rs.getString("name"));

                    account.getServiceLevel().setMaxGroups(rs.getInt("max_groups"));
                    account.getServiceLevel().setMaxTags(rs.getInt("max_tags"));
                    account.getServiceLevel().setMaxDepth(rs.getInt("max_depth"));

                    consumer.accept(account);
                }
            }
        } catch (final SQLException sqlException) {
            throw ErrorTransformer.get("Failed to get account by id", sqlException);
        }
    }

    @Override
    public void get(@Nonnull final Collection<Long> accountIds, @Nonnull final Consumer<Account> consumer) {
        Objects.requireNonNull(accountIds);
        Objects.requireNonNull(consumer);

        final String sql =
                "SELECT a.account_id, a.name, s.max_groups, s.max_tags, s.max_depth FROM accounts a LEFT JOIN "
                        + "service_levels s ON (a.account_id = s.account_id) WHERE a.account_id = ANY (?)";

        final DataSource dataSource = getDataSourceSupplier().get();
        try (final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("bigint", accountIds.toArray()));

            final Account account = new Account();

            try (final ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    account.setId(rs.getLong("account_id"));
                    account.setName(rs.getString("name"));

                    account.getServiceLevel().setMaxGroups(rs.getInt("max_groups"));
                    account.getServiceLevel().setMaxTags(rs.getInt("max_tags"));
                    account.getServiceLevel().setMaxDepth(rs.getInt("max_depth"));

                    consumer.accept(account);
                }
            }
        } catch (final SQLException sqlException) {
            throw ErrorTransformer.get("Failed to get accounts by id", sqlException);
        }
    }

    @Override
    public void getAll(@Nonnull final Consumer<Account> consumer) {
        Objects.requireNonNull(consumer);

        final String sql = "SELECT a.account_id, a.name, s.max_groups, s.max_tags, s.max_depth FROM accounts a JOIN "
                + "service_levels s ON (a.account_id = s.account_id)";

        final DataSource dataSource = getDataSourceSupplier().get();
        try (final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(sql);
             final ResultSet rs = ps.executeQuery()) {

            final Account account = new Account();

            while (rs.next()) {
                account.setId(rs.getLong("account_id"));
                account.setName(rs.getString("name"));

                account.getServiceLevel().setMaxGroups(rs.getInt("max_groups"));
                account.getServiceLevel().setMaxTags(rs.getInt("max_tags"));
                account.getServiceLevel().setMaxDepth(rs.getInt("max_depth"));

                consumer.accept(account);
            }
        } catch (final SQLException sqlException) {
            throw ErrorTransformer.get("Failed to get all accounts", sqlException);
        }
    }

    @Override
    public void add(@Nonnull final Iterable<Account> accounts, @Nonnull final Consumer<Account> consumer) {
        Objects.requireNonNull(accounts);
        Objects.requireNonNull(consumer);

        final int batchSize = 1000;

        final String sql = "INSERT INTO accounts (name) VALUES (?)";

        final DataSource dataSource = getDataSourceSupplier().get();
        final ServiceLevelDao serviceLevelDao = getServiceLevelDaoSupplier().get();
        try (final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            // Need to close the service-level adder before committing.
            try (final CloseableBiConsumer<Long, ServiceLevel> serviceLevelAdder = serviceLevelDao
                    .getAddConsumer(conn)) {

                final Collection<Account> batch = new LinkedList<>();

                for (final Account account : accounts) {
                    ps.setString(1, account.getName());
                    ps.addBatch();
                    batch.add(account);

                    if (batch.size() >= batchSize) {
                        ps.executeBatch();
                        processBatch(ps, batch, serviceLevelAdder, consumer);
                    }
                }
                if (!batch.isEmpty()) {
                    ps.executeBatch();
                    processBatch(ps, batch, serviceLevelAdder, consumer);
                }
            }

            conn.commit();
        } catch (final SQLException sqlException) {
            throw ErrorTransformer.get("Failed to add accounts", sqlException);
        }
    }

    private void processBatch(
            @Nonnull final PreparedStatement ps, @Nonnull final Collection<Account> batch,
            @Nonnull final CloseableBiConsumer<Long, ServiceLevel> serviceLevelAdder,
            @Nonnull final Consumer<Account> consumer) throws SQLException {
        try (final ResultSet rs = ps.getGeneratedKeys()) {
            final Iterator<Account> batchIter = batch.iterator();
            while (rs.next() && batchIter.hasNext()) {
                final long accountId = rs.getLong(1);

                final Account account = batchIter.next();
                account.setId(accountId);

                consumer.accept(account);
                serviceLevelAdder.accept(accountId, account.getServiceLevel());
            }
            batch.clear();
        }
    }

    @Override
    public int remove(@Nonnull final Long accountId) {
        // NOTE: We don't call the other remove method here since it requires the creation of a new collection.
        Objects.requireNonNull(accountId);

        final String sql = "DELETE FROM accounts WHERE account_id = ?";

        int removed = 0;

        final DataSource dataSource = getDataSourceSupplier().get();
        try (final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            removed += ps.executeUpdate();
            conn.commit();
        } catch (final SQLException sqlException) {
            throw ErrorTransformer.get("Failed to remove accounts", sqlException);
        }

        return removed;
    }

    @Override
    public int remove(@Nonnull final Collection<Long> accountIds) {
        Objects.requireNonNull(accountIds);

        final String sql = "DELETE FROM accounts WHERE account_id = ANY (?)";

        int removed = 0;

        final DataSource dataSource = getDataSourceSupplier().get();
        try (final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("bigint", accountIds.toArray()));
            removed += ps.executeUpdate();
            conn.commit();
        } catch (final SQLException sqlException) {
            throw ErrorTransformer.get("Failed to remove accounts", sqlException);
        }

        return removed;
    }
}
