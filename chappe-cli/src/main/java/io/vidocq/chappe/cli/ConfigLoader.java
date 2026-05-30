package io.vidocq.chappe.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.vidocq.chappe.cli.yaml.YamlReader;

/** Loads a {@link ChappeConfig} from a YAML file on disk. */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Reads the UTF-8 file and maps it to a {@code ChappeConfig}. */
    public static ChappeConfig load(Path file) throws IOException {
        String content = Files.readString(file);
        return ChappeConfig.from(YamlReader.parse(content));
    }
}
