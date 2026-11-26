package ru.rpcore.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.bukkit.Bukkit;
import ru.rpcore.config.CoreConfig;
import ru.rpcore.RPCorePlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * MariaDB + HikariCP.
 *
 * <p>Методы {@code *Async} возвращают {@link CompletableFuture}, выполняются в отдельном
 * пуле потоков БД и безопасны для вызова из любого потока, в том числе из региона Folia.
 * Блокирующие методы ({@link #execute}, {@link #update}, {@link #query}) вызывайте только
 * из async-потоков, иначе вы заморозите тики региона.</p>
 *
 * <p>Результат future приходит в потоке БД. Чтобы вернуться к игроку/миру, используйте
 * {@code scheduler.entity(...)} / {@code scheduler.region(...)}.</p>
 */
public final class Database implements AutoCloseable {

    private final RPCorePlugin plugin;
    private final HikariDataSource dataSource;
    private final ThreadPoolExecutor executor;

    public Database(RPCorePlugin plugin, CoreConfig.Db cfg) {
        this.plugin = plugin;

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("RPCore-Pool");
        hc.setDriverClassName("org.mariadb.jdbc.Driver");
        hc.setJdbcUrl(cfg.jdbcUrl());
        hc.setUsername(cfg.user());
        hc.setPassword(cfg.password());
        hc.setMaximumPoolSize(cfg.maxPoolSize());
        hc.setMinimumIdle(Math.min(cfg.minIdle(), cfg.maxPoolSize()));
        hc.setConnectionTimeout(cfg.connectionTimeoutMs());
        hc.setIdleTimeout(cfg.idleTimeoutMs());
        hc.setMaxLifetime(cfg.maxLifetimeMs());
        hc.setLeakDetectionThreshold(cfg.leakDetectionMs());

        // Hikari ищет драйвер через context classloader - подставляем загрузчик плагина,
        // в котором лежат библиотеки из plugin.yml.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(plugin.getClass().getClassLoader());
        try {
            this.dataSource = new HikariDataSource(hc); // бросит исключение, если БД недоступна
        } finally {
            thread.setContextClassLoader(previous);
        }

        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                cfg.maxPoolSize(), cfg.maxPoolSize(), 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "RPCore-DB-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.executor.allowCoreThreadTimeOut(true);
    }

    // ------------------------------------------------------------ blocking

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Выполнить работу с соединением (autocommit включён). Только из async-потока! */
    public <T> T execute(SqlFunction<Connection, T> work) throws SQLException {
        warnIfTickThread();
        try (Connection conn = dataSource.getConnection()) {
            return work.apply(conn);
        }
    }

    /** То же, но в транзакции: commit при успехе, rollback при исключении. */
    public <T> T transaction(SqlFunction<Connection, T> work) throws SQLException {
        warnIfTickThread();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (Throwable ex) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** INSERT/UPDATE/DELETE. Возвращает число затронутых строк. */
    public int update(String sql, Object... params) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    public <T> List<T> query(String sql, SqlMapper<T> mapper, Object... params) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapper.map(rs));
                    }
                    return list;
                }
            }
        });
    }

    public <T> Optional<T> queryOne(String sql, SqlMapper<T> mapper, Object... params) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.<T>empty();
                }
            }
        });
    }

    // ------------------------------------------------------------ async

    public <T> CompletableFuture<T> executeAsync(SqlFunction<Connection, T> work) {
        return supply(() -> execute(work));
    }

    public <T> CompletableFuture<T> transactionAsync(SqlFunction<Connection, T> work) {
        return supply(() -> transaction(work));
    }

    public CompletableFuture<Integer> updateAsync(String sql, Object... params) {
        return supply(() -> update(sql, params));
    }

    public <T> CompletableFuture<List<T>> queryAsync(String sql, SqlMapper<T> mapper, Object... params) {
        return supply(() -> query(sql, mapper, params));
    }

    public <T> CompletableFuture<Optional<T>> queryOneAsync(String sql, SqlMapper<T> mapper, Object... params) {
        return supply(() -> queryOne(sql, mapper, params));
    }

    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
        } catch (RuntimeException rejected) { // пул уже закрыт
            future.completeExceptionally(rejected);
        }
        return future;
    }

    // ------------------------------------------------------------ migrations

    /**
     * Применить миграции модуля. Вызывайте из onEnable (там блокировка допустима).
     * Версии, уже записанные в {@code rp_migrations}, пропускаются.
     */
    public void migrate(String module, List<Migration> migrations) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            try (var st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS rp_migrations ("
                        + "module VARCHAR(64) NOT NULL,"
                        + "version INT NOT NULL,"
                        + "applied_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (module, version)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }

            int current = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(version), 0) FROM rp_migrations WHERE module = ?")) {
                ps.setString(1, module);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) current = rs.getInt(1);
                }
            }

            List<Migration> pending = migrations.stream()
                    .filter(m -> m.version() > 0)
                    .sorted((a, b) -> Integer.compare(a.version(), b.version()))
                    .toList();

            for (Migration m : pending) {
                if (m.version() <= current) continue;
                for (String sql : m.statements()) {
                    try (var st = conn.createStatement()) {
                        st.executeUpdate(sql);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO rp_migrations (module, version, applied_at) VALUES (?, ?, ?)")) {
                    ps.setString(1, module);
                    ps.setInt(2, m.version());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                plugin.getLogger().info("[DB] " + module + ": применена миграция v" + m.version());
            }
        }
    }

    // ------------------------------------------------------------ status

    /** Проверка связи, возвращает пинг в мс. */
    public CompletableFuture<Long> pingAsync() {
        return supply(() -> {
            long start = System.nanoTime();
            try (Connection conn = dataSource.getConnection();
                 var st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
            return (System.nanoTime() - start) / 1_000_000L;
        });
    }

    public record PoolStats(int active, int idle, int total, int waiting) {}

    public PoolStats poolStats() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) return new PoolStats(0, 0, 0, 0);
        return new PoolStats(pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getTotalConnections(), pool.getThreadsAwaitingConnection());
    }

    // ------------------------------------------------------------ util

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof UUID uuid) p = uuid.toString();
            ps.setObject(i + 1, p);
        }
    }

    private void warnIfTickThread() {
        if (plugin.config().debug() && Bukkit.isPrimaryThread()) {
            plugin.getLogger().log(Level.WARNING,
                    "Блокирующий вызов БД из тика региона! Используйте *Async-методы.",
                    new Throwable("stack"));
        }
    }

    /** Удобно для unwrap CompletionException в логах. */
    public static Throwable unwrap(Throwable ex) {
        return (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
    }

    public ExecutorService executor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
