package io.vidocq.chappe.cli;

import io.vidocq.chappe.cli.yaml.YamlReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Charge un {@link ChappeConfig} depuis un fichier YAML sur disque. */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Lit le fichier UTF-8 et le projette sur un {@code ChappeConfig}. */
    public static ChappeConfig load(Path file) throws IOException {
        String content = Files.readString(file);
        return ChappeConfig.from(YamlReader.parse(content));
    }
}
