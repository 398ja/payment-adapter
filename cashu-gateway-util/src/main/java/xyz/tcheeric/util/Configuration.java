package xyz.tcheeric.util;

import lombok.NonNull;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Configuration {

    private final static Properties properties = new Properties();
    private final static String CONFIG_FILE_PATH = "gateway.properties";

    static {
        String configFilePath = System.getProperty(CONFIG_FILE_PATH);
        try (InputStream input = (configFilePath != null) ? new FileInputStream(configFilePath) : Configuration.class.getClassLoader().getResourceAsStream(CONFIG_FILE_PATH)) {
            if (input == null) {
                throw new IOException("Unable to find " + CONFIG_FILE_PATH);
            }
            properties.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static String getProperty(@NonNull String key) {
        return properties.getProperty(key);
    }
}
