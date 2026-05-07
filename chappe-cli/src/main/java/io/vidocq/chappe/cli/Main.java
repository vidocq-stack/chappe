package io.vidocq.chappe.cli;

/** Point d'entrée du CLI {@code chappe}. */
public final class Main {

    private Main() {}

    public static void main(String[] argv) throws Exception {
        CliArgs args;
        try {
            args = CliArgs.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(CliArgs.helpText());
            System.exit(2);
            return;
        }
        if (args.help()) {
            System.out.print(CliArgs.helpText());
            return;
        }
        ServeCommand.run(args);
    }
}
