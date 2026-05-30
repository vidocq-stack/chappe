package io.vidocq.chappe.cli.yaml;

/** YAML parsing error carrying the {@code line:col} position for diagnostics. */
public final class YamlParseException extends RuntimeException {

    private final int line;
    private final int column;

    public YamlParseException(int line, int column, String message) {
        super("YAML error at " + line + ":" + column + " — " + message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
