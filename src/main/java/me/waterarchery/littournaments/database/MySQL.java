package me.waterarchery.littournaments.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.waterarchery.littournaments.LitTournaments;
import org.bukkit.configuration.file.FileConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class MySQL extends Database {

    private final HikariConfig hikariConfig = new HikariConfig();
    private DataSource dataSource;

    public MySQL(LitTournaments instance) {
        super(instance);
    }

    public Connection getSQLConnection() {
        try {
            Connection connection = dataSource.getConnection();
            // Validate connection
            if (connection != null && !connection.isClosed() && connection.isValid(5)) {
                return connection;
            }
            LitTournaments.getLitLibs().getLogger().error("Invalid MySQL connection obtained from pool");
        } catch (SQLException ex) {
            LitTournaments.getLitLibs().getLogger().error("MySQL exception on getSQLConnection: " + ex.getMessage());
        }
        return null;
    }

    @Override
    public void initialize() {
        FileConfiguration config = instance.getConfig();

        hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
        hikariConfig.setJdbcUrl(String.format("jdbc:mariadb://%s:%s/%s",
                config.getString("Database.MySQL.host"),
                config.getString("Database.MySQL.port"),
                config.getString("Database.MySQL.database")
        ));

        hikariConfig.setUsername(config.getString("Database.MySQL.user"));
        hikariConfig.setPassword(config.getString("Database.MySQL.password"));

        hikariConfig.addDataSourceProperty("useUnicode", "true");
        hikariConfig.addDataSourceProperty("characterEncoding", "utf8");
        hikariConfig.addDataSourceProperty("autoReconnect", "true");
        hikariConfig.addDataSourceProperty("useSSL", "false");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setValidationTimeout(5000);
        hikariConfig.setLeakDetectionThreshold(60000);

        dataSource = new HikariDataSource(hikariConfig);
    }

}
