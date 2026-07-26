package com.osuserverlist.bjar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.engine.ProductionLevel;
import com.osuserverlist.bjar.modules.assets.AchievementDownloader;
import com.osuserverlist.bjar.modules.assets.DefaultAssetsDownloader;
import com.osuserverlist.bjar.modules.datastore.DatabaseManager;
import com.osuserverlist.bjar.modules.datastore.DatabaseManager.ServerTimezone;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.main.Application;
import com.osuserverlist.bjar.modules.main.Application.BuildInfo;
import com.osuserverlist.bjar.modules.main.Logging.LoggerConfiguration;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService;
import com.osuserverlist.bjar.modules.osu.BssStorage;
import com.osuserverlist.bjar.modules.recalc.RecalcRunnable;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Bancho.jar - An open-source osu! server implementation in Java.
 * Entrypoint
 */
public class App {

    public static final String MAIN_PACKAGE = "com.osuserverlist.bjar";
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    /**
     * Main access to the server instance. This is a singleton and should be used to access the server from anywhere in the code.
     */
    public static Server server = new Server();

    public void main(String[] args) {
        System.out.println(Application.HEADER);
        logger.info("Bancho.jar <v" + BuildInfo.VERSION + "> built on (" + BuildInfo.BUILD_TIME + ")");
        logger.info("Running on Java <" + BuildInfo.JAVA_VERSION + "> with Gradle <" + BuildInfo.GRADLE_VERSION + ">");

        Dotenv dotenv = Dotenv.configure().systemProperties().ignoreIfMissing().load();

        ProductionLevel level = ProductionLevel.fromCode(dotenv.get("LEVEL", "PROD"));
        LoggerConfiguration loggerConfig = new LoggerConfiguration(level);
        loggerConfig.apply();

        DatabaseManager database = new DatabaseManager();

        database.connect(config -> {
            config.setHost(dotenv.get("DB_HOST"));
            config.setUser(dotenv.get("DB_USER"));
            config.setPassword(dotenv.get("DB_PASS"));
            config.setDatabase(dotenv.get("DB_NAME"));
            config.setServerTimezone(ServerTimezone.valueOf(dotenv.get("DB_TIMEZONE")));
        });

        Redis redis = new Redis(config -> {
            config.setHost(dotenv.get("REDIS_HOST"));
            config.setPort(Integer.parseInt(dotenv.get("REDIS_PORT")));
            config.setPassword(dotenv.get("REDIS_PASS"));
            config.setDatabase(Integer.parseInt(dotenv.get("REDIS_DB", "0")));
        });

        redis.connect();

        if (args.length > 0 && args[0].equalsIgnoreCase("--recalc")) {
            boolean force = false;
            if (args.length > 1 && args[1].equalsIgnoreCase("--force")) {
                force = true;
            }

            App.server.performance.loadCalculatorFromString(dotenv.get("PP_CALCULATOR"));

            RecalcRunnable recalcRunnable = new RecalcRunnable(force);
            recalcRunnable.run();
            return;
        }

        try {
            Files.createDirectories(Path.of("data/maps"));
            Files.createDirectories(Path.of("data/replays"));
            Files.createDirectories(Path.of("data/ss"));
            Files.createDirectories(Path.of("data/assets/avatars"));
            Files.createDirectories(Path.of("data/assets/medals/client"));

            // Beatmap Submission System storage
            BssStorage.initialize();

            AchievementDownloader downloader = new AchievementDownloader();
            DefaultAssetsDownloader defaultAssetsDownloader = new DefaultAssetsDownloader();
            defaultAssetsDownloader.run();
            downloader.run();
        } catch (IOException e) {
            logger.error("Failed to initialize data structure", e);
        }

        // Main bjar entrypoint
        server.start(config -> {
            config.setDomain(dotenv.get("DOMAIN"));
            config.setPort(Integer.parseInt(dotenv.get("PORT", "8200")));
            config.setLevel(ProductionLevel.fromCode(dotenv.get("LEVEL", "PROD")));

            config.setPerformanceCalculator(dotenv.get("PP_CALCULATOR"));
            config.setGeoProvider(dotenv.get("GEO_PROVIDER"));

            config.setOsuApiKey(dotenv.get("OSU_API_KEY"));
            config.setDlEndpoint(dotenv.get("DIRECT_DL"));
            config.setSearchEndpoint(dotenv.get("DIRECT_SEARCH"));

            config.setIngameRegistrationEnabled(Boolean.parseBoolean(dotenv.get("INGAME_REGISTRATION_ENABLED")));

            config.setIrcEnabled(Boolean.parseBoolean(dotenv.get("IRC_ENABLED", "false")));
            config.setIrcPort(Integer.parseInt(dotenv.get("IRC_PORT", "6667")));

            config.setOnlySendHelpDm(Boolean.parseBoolean(dotenv.get("ONLY_SEND_HELP_DM", "false")));

            config.setBssEnabled(Boolean.parseBoolean(dotenv.get("BSS_ENABLED", "true")));
            config.setBssIdOffset(Integer.parseInt(
                    dotenv.get("BSS_ID_OFFSET", String.valueOf(BeatmapSubmissionService.DEFAULT_ID_OFFSET))));
            config.setBssMaxPendingSets(Integer.parseInt(dotenv.get("BSS_MAX_PENDING_SETS", "10")));
            config.setBssMaxUploadSizeMb(Integer.parseInt(dotenv.get("BSS_MAX_UPLOAD_SIZE_MB", "100")));
        });

        Runnable shutdownHook = () -> {
            server.stop();
        };
        
        Runtime.getRuntime().addShutdownHook(new Thread(shutdownHook));
    }
}
