package io.vidocq.chappe.bench.oop;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

public final class JettyMain {
    private JettyMain() {}

    public static void main(String[] args) throws Exception {
        int port = OopArgs.port(args, 8080);
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("0.0.0.0");
        connector.setPort(port);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract.NonBlocking() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request req,
                                  org.eclipse.jetty.server.Response res,
                                  Callback cb) {
                res.setStatus(200);
                res.getHeaders().put("Content-Type", "text/plain");
                Content.Sink.write(res, true, "ok", cb);
                return true;
            }
        });
        server.start();
        System.out.println("jetty listening on :" + connector.getLocalPort());
        server.join();
    }
}
