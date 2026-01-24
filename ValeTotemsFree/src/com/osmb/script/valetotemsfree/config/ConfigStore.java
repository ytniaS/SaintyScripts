package com.osmb.script.valetotemsfree.config;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class ConfigStore {

    private static final String FILE_NAME = "config.properties";

    private static final Path CONFIG_DIR = Paths.get(
            System.getProperty("user.home"),
            "OSMB",
            "Config",
            "ValeTotemsFree"
    );

    private static final Path CONFIG_FILE = CONFIG_DIR.resolve(FILE_NAME);

    private ConfigStore() {
        // utility class
    }

    public static void save(Properties config) {
        try {
            Files.createDirectories(CONFIG_DIR);

            try (FileOutputStream out = new FileOutputStream(CONFIG_FILE.toFile())) {
                config.store(out, "Vale Totems Free Configuration");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Properties load() {
        Properties config = new Properties();

        if (!Files.exists(CONFIG_FILE)) {
            return config;
        }

        try (FileInputStream in = new FileInputStream(CONFIG_FILE.toFile())) {
            config.load(in);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return config;
    }
}
