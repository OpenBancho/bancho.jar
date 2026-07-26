package com.osuserverlist.bjar.modules.main;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;

public final class Configuration {
    private static final Toml TOML = new Toml();
    private static final TomlWriter WRITER = new TomlWriter();

    public static <T> T load(String path, Class<T> clazz, Supplier<T> defaults) {
        File file = new File(path);

        if (file.exists()) {
            return TOML.read(file).to(clazz);
        }

        T config = defaults.get();

        file.getParentFile().mkdirs();

        try {
            WRITER.write(config, file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config " + path, e);
        }

        return config;
    }
}
