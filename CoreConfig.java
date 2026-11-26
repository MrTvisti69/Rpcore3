package ru.rpcore.config;

import org.bukkit.configuration.file.FileConfiguration;

/** Типизированный снимок config.yml. Неизменяем - при /rpcore reload создаётся новый. */
public final class CoreConfig {

    public record Db(String host, int port, String name, String user, String password, String jdbcParams,
                     int maxPoolSize, int minIdle, long connectionTimeoutMs, long idleTimeoutMs,
                     long maxLifetimeMs, long leakDetectionMs) {

        public String jdbcUrl() {
            String url = "jdbc:mariadb://" + host + ":" + port + "/" + name;
            if (jdbcParams != null && !jdbcParams.isBlank()) {
                url += "?" + jdbcParams;
            }
            return url;
        }
    }

    private final String defaultLanguage;
    private final boolean autoDetectLanguage;
    private final int autosaveSeconds;
    private final boolean debug;
    private final Db db;

    private CoreConfig(String defaultLanguage, boolean autoDetectLanguage, int autosaveSeconds,
                       boolean debug, Db db) {
        this.defaultLanguage = defaultLanguage;
        this.autoDetectLanguage = autoDetectLanguage;
        this.autosaveSeconds = autosaveSeconds;
        this.debug = debug;
        this.db = db;
    }

    public static CoreConfig from(FileConfiguration c) {
        Db db = new Db(
                c.getString("database.host", "127.0.0.1"),
                c.getInt("database.port", 3306),
                c.getString("database.name", "rp"),
                c.getString("database.user", "rp"),
                c.getString("database.password", ""),
                c.getString("database.jdbc-params", ""),
                Math.max(1, c.getInt("database.pool.maximum-pool-size", 10)),
                Math.max(1, c.getInt("database.pool.minimum-idle", 2)),
                c.getLong("database.pool.connection-timeout-ms", 10_000),
                c.getLong("database.pool.idle-timeout-ms", 600_000),
                c.getLong("database.pool.max-lifetime-ms", 1_740_000),
                c.getLong("database.pool.leak-detection-ms", 0)
        );
        return new CoreConfig(
                c.getString("language.default", "ru").toLowerCase(),
                c.getBoolean("language.auto-detect", true),
                Math.max(30, c.getInt("cache.autosave-seconds", 300)),
                c.getBoolean("debug", false),
                db
        );
    }

    public String defaultLanguage() { return defaultLanguage; }
    public boolean autoDetectLanguage() { return autoDetectLanguage; }
    public int autosaveSeconds() { return autosaveSeconds; }
    public boolean debug() { return debug; }
    public Db db() { return db; }
}
