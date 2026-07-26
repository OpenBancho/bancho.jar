package com.osuserverlist.bjar.models;

import java.util.List;

import com.osuserverlist.bjar.models.osu.OsuClientModels.ActionStatus;
import com.osuserverlist.bjar.modules.main.Configuration;
import com.zaxxer.hikari.HikariConfig;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ConfigModels {
    @Data
    public static class DatabaseConfiguration {
        private boolean logSql = false;
        private boolean cachePreparedStatements = true;
        private int preparedStatementCacheSize = 250;
        private int preparedStatementCacheSqlLimit = 2048;
        private boolean autoReconnect = true;
        private boolean useServerPrepStmts = true;
        private boolean useLocalSessionState = true;
        private boolean rewriteBatchedStatements = true;
        private boolean cacheResultSetMetadata = true;
        private boolean cacheStatements = true;

        private boolean useSSL = false;
        private boolean requireSSL = false;
        private int connectionTimeout = 30000; // 30 seconds
        private String characterEncoding = "UTF-8";
        private long idleTimeout = 600000; // 10 minutes
        private long maxLifetime = 1800000; // 30 minutes
        private int maximumPoolSize = 10;
        private int validationTimeout = 5000; // 5 seconds
        private int leakDetectionThreshold = 0; // disabled by default
        private boolean allowPoolSuspension = false;
        private boolean autoCommit = true;

        public void apply(HikariConfig config) {
            config.setAutoCommit(autoCommit);
            config.setAllowPoolSuspension(allowPoolSuspension);
            config.setConnectionTimeout(connectionTimeout);
            config.setIdleTimeout(idleTimeout);
            config.setMaxLifetime(maxLifetime);
            config.setMaximumPoolSize(maximumPoolSize);
            config.setValidationTimeout(validationTimeout);
            config.setLeakDetectionThreshold(leakDetectionThreshold);

            config.addDataSourceProperty("cachePrepStmts", cachePreparedStatements);
            config.addDataSourceProperty("prepStmtCacheSize", preparedStatementCacheSize);
            config.addDataSourceProperty("prepStmtCacheSqlLimit", preparedStatementCacheSqlLimit);
            config.addDataSourceProperty("autoReconnect", autoReconnect);
            config.addDataSourceProperty("useServerPrepStmts", useServerPrepStmts);
            config.addDataSourceProperty("useLocalSessionState", useLocalSessionState);
            config.addDataSourceProperty("rewriteBatchedStatements", rewriteBatchedStatements);
            config.addDataSourceProperty("cacheResultSetMetadata", cacheResultSetMetadata);
            config.addDataSourceProperty("cacheStatements", cacheStatements);

            config.addDataSourceProperty("useSSL", useSSL);
            config.addDataSourceProperty("requireSSL", requireSSL);
            config.addDataSourceProperty("characterEncoding", characterEncoding);
        }

        public static DatabaseConfiguration load() {
            return Configuration.load(
                    ".config/database.toml",
                    DatabaseConfiguration.class,
                    DatabaseConfiguration::new);
        }
    }

    @Data
    public static class ServerConfiguration {
        private String serverName = "bancho.jar";
        private List<String> seasonalBackgrounds = List.of("https://i.ibb.co/Gfhch3nW/wp4048636.jpg");

        private MenuIcon menuIcon = new MenuIcon();
        private WelcomeMessage welcomeMessage = new WelcomeMessage();

        @Data
        public static class WelcomeMessage {
            private boolean botEnabled = true;
            private String botMessage = "Welcome to bancho.jar v(%version%)";
            private boolean notificationEnabled = true;
            private String notificationMessage = "Welcome to bancho.jar v(%version%)";
        }

        @Data
        public static class MenuIcon {
            private String outlink = "";
            private String imageUrl = "";
        }

        public static ServerConfiguration load() {
            return Configuration.load(
                    ".config/server.toml",
                    ServerConfiguration.class,
                    ServerConfiguration::new);
        }
    }

    @Data
    public static class PresenceConfiguration {
        private List<PresenceInfo> presenceInfos = List.of(new PresenceInfo(ActionStatus.EDITING, "bancho.jars source code"), new PresenceInfo(ActionStatus.WATCHING, "some gameplay"), new PresenceInfo(ActionStatus.TESTING, "some beatmaps"), new PresenceInfo(ActionStatus.SUBMITTING, "some beatmaps"));

        @AllArgsConstructor
        @NoArgsConstructor
        @Data
        public static class PresenceInfo {
            private ActionStatus actionStatus;
            private String details;
        }

        public static PresenceConfiguration load() {
            return Configuration.load(
                    ".config/presence.toml",
                    PresenceConfiguration.class,
                    PresenceConfiguration::new);
        }
    }
}
