package xyz.tcheeric.util;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class ConfigUtil {

    private final String prefix;

    public String get(String key) {
        return Configuration.getProperty(prefix + "." + key);
    }
}
