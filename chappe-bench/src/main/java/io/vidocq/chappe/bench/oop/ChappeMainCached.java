package io.vidocq.chappe.bench.oop;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;

/**
 * Variant of {@link ChappeMain} that serves a prebuilt {@link Response}
 * shared across all requests (zero-allocation on the response side).
 *
 * <p>Lets you measure the impact of the per-request allocations
 * {@code Builder → Headers$Entry → DefaultHeaders → DefaultResponse}
 * identified in the JFR profile.
 */
public final class ChappeMainCached {
    private ChappeMainCached() {}

    /** Prebuilt once — Response is documented as immutable. */
    private static final Response CACHED_OK = Response.ok("ok");

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Server server = Server.builder()
                .port(port)
                .host("0.0.0.0")
                .handler(_ -> CACHED_OK)
                .build();
        server.start();
        System.out.println("chappe-jvm-cached listening on :" + server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "chappe-shutdown"));
        Thread.currentThread().join();
    }
}
