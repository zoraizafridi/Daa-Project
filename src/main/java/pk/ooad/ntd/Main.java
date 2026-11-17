package pk.ooad.ntd;

import pk.ooad.ntd.domain.TopologyLoader;
import pk.ooad.ntd.security.SecurityPolicy;
import pk.ooad.ntd.service.IncidentResponseFacade;
import pk.ooad.ntd.util.ConfigManager;
import pk.ooad.ntd.util.InvalidConfigException;
import pk.ooad.ntd.web.WebServer;

public class Main {
    private static final String VERSION = "1.0";

    public static void main(String[] args) {
        boolean web = false;
        int port = 8080;
        long seed = 42L;
        String profile = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--web" -> web = true;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--config" -> profile = args[++i];
                    default -> {
                        usage();
                        return;
                    }
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
            usage();
            return;
        }
        if (!web || port < 0 || port > 65535) {
            usage();
            return;
        }
        ConfigManager config = ConfigManager.getInstance();
        if (profile != null) {
            try {
                config.overlay(profile);
            } catch (InvalidConfigException ex) {
                System.out.println(ex.getMessage());
                System.exit(2);
                return;
            }
        }
        SecurityPolicy policy = SecurityPolicy.from(config);
        System.out.println(policy.banner(VERSION));
        try {
            policy.enforce(config);
        } catch (IllegalStateException ex) {
            System.out.println(ex.getMessage());
            System.exit(2);
            return;
        }
        for (String warning : policy.warnings(config)) {
            System.out.println("warning: " + warning);
        }
        IncidentResponseFacade facade = new IncidentResponseFacade(TopologyLoader.loadSample(), seed);
        WebServer server = new WebServer(facade, port);
        server.start(port);
    }

    private static void usage() {
        System.out.println("Usage: java -jar threat-detection.jar --web [--port N] [--seed N] [--config file]");
    }
}
