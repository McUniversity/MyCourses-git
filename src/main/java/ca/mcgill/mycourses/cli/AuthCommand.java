package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.service.AuthService;
import ca.mcgill.mycourses.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Cookie-based authentication — no OAuth client_id required.
 *
 * Brightspace session cookies (d2l_sessionVal + d2l_authCookieVal) are set in your
 * browser when you log into myCourses normally. This command saves those cookies so
 * the tool can make API calls on your behalf.
 *
 * Cookies expire when your browser session ends or after McGill's timeout (~8 hours).
 * Re-run `mycourses auth` whenever pull gives you a 401 Unauthorized error.
 */
@Command(
    name = "auth",
    mixinStandardHelpOptions = true,
    description = "Authenticate using browser session cookies (no OAuth client_id needed)."
)
public class AuthCommand implements Callable<Integer> {

    @Option(names = {"--no-verify"}, description = "Skip the API verification step after saving.")
    private boolean noVerify = false;

    @Override
    public Integer call() throws Exception {
        ConfigService cs     = new ConfigService();
        AppConfig     config = cs.loadConfig();

        printInstructions(config.getTenantUrl());

        String sessionVal    = prompt("Paste d2lSessionVal       : ");
        String authCookieVal = prompt("Paste d2lSecureSessionVal : ");

        if (sessionVal.isBlank()) {
            System.err.println("Error: d2l_sessionVal cannot be empty.");
            return 1;
        }

        AuthService auth = new AuthService(config, cs);
        auth.saveCookies(sessionVal.trim(), authCookieVal.trim());
        System.out.println("\nCookies saved to ~/.mycourses/tokens.json");

        if (!noVerify) {
            return verify(config, auth);
        }
        return 0;
    }

    // -------------------------------------------------------------------------

    private int verify(AppConfig config, AuthService auth) {
        System.out.print("Verifying against myCourses API... ");
        try {
            String whoamiUrl = config.getTenantUrl()
                + "/d2l/api/lp/" + config.getLpVersion() + "/users/whoami";

            OkHttpClient http = new OkHttpClient();
            Request.Builder builder = new Request.Builder()
                .url(whoamiUrl)
                .header("Accept", "application/json")
                .get();
            auth.addAuthHeader(builder);

            try (Response resp = http.newCall(builder.build()).execute()) {
                if (resp.code() == 401 || resp.code() == 403) {
                    System.out.println("FAILED (HTTP " + resp.code() + ")");
                    System.err.println(
                        "The cookies were rejected. Make sure you copied them from an active\n" +
                        "myCourses session and that you are still logged in.");
                    return 1;
                }
                if (!resp.isSuccessful()) {
                    System.out.println("FAILED (HTTP " + resp.code() + ")");
                    System.err.println("Unexpected error. Try again or use --no-verify to skip this check.");
                    return 1;
                }
                JsonNode user = new ObjectMapper().readTree(resp.body().string());
                String name = user.path("FirstName").asText("")
                    + " " + user.path("LastName").asText("");
                System.out.println("OK");
                System.out.println("Logged in as: " + name.trim());
                System.out.println("Ready. Run: mycourses pull");
            }
        } catch (Exception e) {
            System.out.println("ERROR");
            System.err.println("Verification failed: " + e.getMessage());
            System.err.println("Cookies were saved anyway. Run `mycourses pull` to try.");
        }
        return 0;
    }

    private static void printInstructions(String tenantUrl) {
        System.out.println("=== How to get your myCourses session cookies ===");
        System.out.println();
        System.out.println("1. Open " + tenantUrl + " in your browser and log in.");
        System.out.println();
        System.out.println("2. Open DevTools:");
        System.out.println("   Chrome/Edge : F12  →  Application tab  →  Storage > Cookies");
        System.out.println("                        → " + tenantUrl);
        System.out.println("   Firefox     : F12  →  Storage tab  →  Cookies");
        System.out.println("                        → " + tenantUrl);
        System.out.println();
        System.out.println("3. Find and copy the Value column for each of these two cookies:");
        System.out.println("   • d2lSessionVal");
        System.out.println("   • d2lSecureSessionVal");
        System.out.println();
        System.out.println("   (They are long strings — copy the entire value.)");
        System.out.println();
        System.out.println("Note: cookies expire when your browser session ends (~8 hours).");
        System.out.println("      Re-run `mycourses auth` if you get a 401 Unauthorized error.");
        System.out.println();
    }

    /** Prompts the user and reads a line; uses Console if available (masks nothing — values are pasted). */
    private static String prompt(String label) {
        System.out.print(label);
        System.out.flush();
        // Console is null when running inside an IDE or piped stdin
        java.io.Console console = System.console();
        if (console != null) {
            return console.readLine();
        }
        return new Scanner(System.in).nextLine();
    }
}
