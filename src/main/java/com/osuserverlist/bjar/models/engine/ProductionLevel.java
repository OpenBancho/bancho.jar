package com.osuserverlist.bjar.models.engine;

public enum ProductionLevel {

    DEVELOPMENT("DEV"),
    PRODUCTION("PROD");

    private final String code;

    ProductionLevel(String code) {
        this.code = code;
    }

    public static ProductionLevel fromCode(String code) {
        for (ProductionLevel level : values()) {
            if (level.code.equalsIgnoreCase(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown production level code: " + code);
    }

}
