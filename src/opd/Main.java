package opd;

import opd.db.Database;
import opd.service.OPDService;
import opd.web.WebServer;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MediFlow - Hospital OPD Management System.
 * Run this class, then open http://localhost:8080
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Path web = WebServer.findWebDir();
        if (!Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("Cannot find web/index.html. Run the program from the project folder "
                    + "(the folder that contains 'web', 'src' and 'lib').");
            System.exit(1);
        }

        Database db = new Database("./data/opd");
        OPDService service = new OPDService(db);
        WebServer server = new WebServer(service, web);
        int port = server.start(8080);
        Runtime.getRuntime().addShutdownHook(new Thread(db::close));

        String url = "http://localhost:" + port;
        System.out.println();
        System.out.println("  ==============================================");
        System.out.println("   MediFlow - Hospital OPD System");
        System.out.println("   Database : ./data/opd.mv.db  (H2 SQL)");
        System.out.println("   Open     : " + url);
        System.out.println("   Stop     : press Ctrl + C");
        System.out.println("  ==============================================");

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(new URI(url));
        } catch (Throwable ignored) { /* headless environment: just use the printed URL */ }
    }
}
