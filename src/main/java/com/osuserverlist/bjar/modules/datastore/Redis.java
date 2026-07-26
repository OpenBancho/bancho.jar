package com.osuserverlist.bjar.modules.datastore;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Data;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

public class Redis {
    private static final Logger logger = LoggerFactory.getLogger(Redis.class);

    private static RedisClient redisClient;
    private static RedisConfiguration config;

    public Redis(Consumer<RedisConfiguration> configConsumer) {
        config = new RedisConfiguration();
        configConsumer.accept(config);
    }

    public static RedisClient getClient() {
        if (redisClient == null) {
            throw new IllegalStateException("Redis client not initialized. Call Redis.connect() first.");
        }
        return redisClient;
    }

    public void connect() {
        DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                .database(config.getDatabase());

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            clientConfigBuilder.password(config.getPassword());
        }

        JedisClientConfig clientConfig = clientConfigBuilder.build();

        RedisClient client = RedisClient.builder()
                .hostAndPort(config.getHost(), config.getPort())
                .clientConfig(clientConfig)
                .build();

        String ping = client.ping();
        if (!"PONG".equals(ping)) {
            logger.error("Failed to connect to Redis: PING response was {}", ping);
            System.exit(1);
        }

        logger.info("Connected to Redis ({}:{}, db={})",
                config.getHost(),
                config.getPort(),
                config.getDatabase());

        Redis.redisClient = client;
    }

    @Data
    public static class RedisConfiguration {
        private String host;
        private int port;
        private String password;
        private int database = 0;
    }
}
