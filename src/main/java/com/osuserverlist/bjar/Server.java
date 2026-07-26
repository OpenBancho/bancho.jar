package com.osuserverlist.bjar;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.ConfigModels.ServerConfiguration;
import com.osuserverlist.bjar.models.engine.ProductionLevel;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.calculations.Performance;
import com.osuserverlist.bjar.modules.irc.IrcGateway;
import com.osuserverlist.bjar.modules.irc.IrcServer;
import com.osuserverlist.bjar.modules.main.Application.BuildInfo;
import com.osuserverlist.bjar.modules.main.Commands;
import com.osuserverlist.bjar.modules.main.GeoLocation;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.BanchoWebLogger;
import com.osuserverlist.bjar.modules.osu.OsuAPIHandler;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacketRegistry;
import com.osuserverlist.bjar.modules.packets.ServerPacketEngine;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.RestartPacket;
import com.osuserverlist.bjar.server.AchievementManager;
import com.osuserverlist.bjar.server.ChannelManager;
import com.osuserverlist.bjar.server.MatchManager;
import com.osuserverlist.bjar.server.PlayerManager;
import com.osuserverlist.bjar.server.scheudler.BotPresenceTask;
import com.osuserverlist.bjar.server.scheudler.PlayerCleanupTask;
import com.osuserverlist.bjar.server.scheudler.SendChannelInfoTask;

import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.javalin.Javalin;
import lombok.Data;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    public Player botPlayer;
    public IrcServer ircServer;
    public OsuAPIHandler osuAPIHandler;
    public ServerConfiguration config = ServerConfiguration.load();
    public PlayerManager playerManager = new PlayerManager();
    public ChannelManager channelManager = new ChannelManager();
    public MatchManager matchManager = new MatchManager();
    public AchievementManager achievementManager = new AchievementManager();
    public ScheduledExecutorService executor = Executors.newScheduledThreadPool(6);
    public Performance performance = new Performance();
    public ServerConfig enviromentConfig = new ServerConfig();

    protected Javalin app;

    public void start(Consumer<ServerConfig> configurer) {
        configurer.accept(enviromentConfig);

        ClientPacketRegistry.registerDefaultHandlers();
        ServerPacketEngine.registerHandlers();

        osuAPIHandler = new OsuAPIHandler(enviromentConfig.getOsuApiKey());

        executor.scheduleAtFixedRate(new PlayerCleanupTask(), 0, 60, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(new SendChannelInfoTask(), 0, 8, TimeUnit.SECONDS);

    
        Player botPlayer = playerManager.getBotPlayer(1);

        playerManager.add(botPlayer);
        this.botPlayer = botPlayer;

        executor.scheduleAtFixedRate(new BotPresenceTask(), 0, 60, TimeUnit.SECONDS);

        channelManager.populate();
        achievementManager.populate();


        Commands.registerAnnotatedHandlers("com.osuserverlist.bjar.commands");

        Commands.finalizeCommandRegistration();

        performance.loadCalculatorFromString(enviromentConfig.getPerformanceCalculator());
        GeoLocation.loadProviderFromString(enviromentConfig.getGeoProvider());
        logger.info("Using <{}> as PerformanceCalculator", performance.getCalculatorClassName());

        app = configureJavalin();

        app.start(enviromentConfig.getPort());

        if (enviromentConfig.isIrcEnabled()) {
            ircServer = new IrcServer();
            IrcGateway.setServer(ircServer);
            try {
                ircServer.start(enviromentConfig.getIrcPort());
            } catch (Exception e) {
                logger.error("Failed to start IRC gateway on port <{}>", enviromentConfig.getIrcPort(), e);
            }
        }
    }

    public void stop() {
        Long startTime = System.currentTimeMillis();
        playerManager.getAllSessions().forEach(player -> {
            player.sendPacket(new RestartPacket(10000));
        });

        if (ircServer != null) {
            ircServer.stop(); // Disconnect IRC clients and close the listener
        }

        executor.shutdown(); // Gracefully shutdown the executor service
        app.stop(); // Gracefully stop the Javalin server

        logger.info("Server stopped in <{} ms>", System.currentTimeMillis() - startTime);

    }

    private Javalin configureJavalin() {
        return Javalin.create(config -> {
            config.routes.exception(Exception.class, (e, ctx) -> {
                logger.error("Unhandled exception while processing {} {}",
                        ctx.method(), ctx.path(), e);

                ctx.status(500).result("Internal Server Error");
            });

            config.concurrency.useVirtualThreads = true;
            config.requestLogger.http(new BanchoWebLogger());

            config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
                pluginConfig.withDefinitionConfiguration((version, definition) -> {
                    definition.info(info -> info
                            .title("bancho.jar API")
                            .version(BuildInfo.VERSION)
                            .description("The web api for bancho.jar, an open-source osu! server implementation."));

                    final String finalApiUrl = "https://api." +  enviromentConfig.getDomain();
                    definition.server(server -> server.url(finalApiUrl).description("API Server"));
                });
            }));

            config.registerPlugin(new SwaggerPlugin(cfg -> {
                cfg.uiPath = "/api/docs";
            }));

            // Register annotated handlers for the web server
            WebEngine.registerDefaultHandlers(config);

            if (enviromentConfig.getLevel() == ProductionLevel.DEVELOPMENT) {
                config.bundledPlugins.enableRouteOverview("/routes");
                config.bundledPlugins.enableDevLogging();
            }
        });
    }

    @Data
    public class ServerConfig {
        private String domain;
        private int port;
        private ProductionLevel level;

        private String osuApiKey;

        private String performanceCalculator;
        private String geoProvider;

        private String searchEndpoint;
        private String dlEndpoint;
        private boolean ingameRegistrationEnabled;

        private boolean ircEnabled;
        private int ircPort;

        private boolean onlySendHelpDm;

        /** Beatmap Submission System. */
        private boolean bssEnabled;
        private int bssIdOffset;
        private int bssMaxPendingSets;
        private int bssMaxUploadSizeMb;
    }
}
