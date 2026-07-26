package com.osuserverlist.bjar.modules.datastore;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.ConfigModels.DatabaseConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.ebean.Database;
import lombok.Data;

public class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    private HikariDataSource dataSource;
    private Database database;

    public void connect(Consumer<DatabaseCredentials> consumer) {

        DatabaseCredentials credentials = new DatabaseCredentials();
        consumer.accept(credentials);

        DatabaseConfiguration config = DatabaseConfiguration.load();

        HikariConfig hikari = new HikariConfig();
        config.apply(hikari);

        hikari.setJdbcUrl(
                "jdbc:mysql://" +
                credentials.getHost() +
                ":3306/" +
                credentials.getDatabase() +
                "?serverTimezone=" +
                credentials.getServerTimezone() +
                "&allowPublicKeyRetrieval=true"
        );

        hikari.setUsername(credentials.getUser());
        hikari.setPassword(credentials.getPassword());

        dataSource = new HikariDataSource(hikari);

        database = Database.builder().name("default").dataSource(dataSource).build();
    }

    public Database database() {
        return database;
    }

    public void shutdown() {

        if (dataSource != null) {
            dataSource.close();
        }

        logger.info("Database shut down.");
    }

    @Data
    public static class DatabaseCredentials {
        private String host;
        private String user;
        private String password;
        private String database;
        private ServerTimezone serverTimezone;
    }

    public enum ServerTimezone {
        UTC,
        GMT
    }
}
