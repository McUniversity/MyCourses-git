package ca.mcgill.mycourses.service;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.model.TokenInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * OAuth 2.0 authorization code flow for Brightspace / D2L.
 *
 * Before this works, McGill IT (or a Brightspace admin) must:
 *   1. Register an OAuth2 client in Brightspace Admin Tools > OAuth 2.0
 *   2. Set redirect URI to: http://localhost:9004/callback
 *   3. Grant scopes: core:*:*  enrollment:Course:Read  content:*:*  dropbox:*  news:*:*
 *   4. Provide the resulting client_id (set it in ~/.mycourses/config.json)
 *
 * Official docs: https://docs.valence.desire2learn.com/basic/oauth2.html
 */
public class AuthService {

    // D2L OAuth2 endpoint paths (relative to tenant URL)
    private static final String AUTH_PATH  = "/d2l/lp/auth/oauth2/authorization";
    private static final String TOKEN_PATH = "/d2l/lp/auth/oauth2/token";

    // Must match the redirect URI registered in Brightspace
    private static final int    CALLBACK_PORT = 9004;
    private static final String REDIRECT_URI  = "http://localhost:" + CALLBACK_PORT + "/callback";

    // Read-only scopes needed to fetch enrollments, content, news, and assignments
    private static final String SCOPES =
        "core:*:* enrollment:Course:Read content:*:* dropbox:* news:*:*";

    private final AppConfig config;
    private final ConfigService configService;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public AuthService(AppConfig config, ConfigService configService) {
        this.config = config;
        this.configService = configService;
        this.http = new OkHttpClient();
        this.mapper = new ObjectMapper();
    }

    /**
     * Full OAuth authorization code flow:
     *   1. Open browser to Brightspace login page
     *   2. Start a local HTTP server to capture the redirect callback
     *   3. Exchange the authorization code for access + refresh tokens
     *   4. Persist tokens to ~/.mycourses/tokens.json (owner-read-only)
     */
    public TokenInfo login() throws Exception {
        if (config.getClientId() == null || config.getClientId().isBlank()) {
            throw new IllegalStateException(
                "OAuth client_id is not set.\n" +
                "Edit ~/.mycourses/config.json and set the 'clientId' field.\n" +
                "You need an OAuth2 client registered with McGill Brightspace admin.");
        }

        String state  = randomState();
        String authUrl = buildAuthUrl(state);

        System.out.println("Opening browser for McGill myCourses login...");
        System.out.println("If the browser did not open, visit this URL manually:\n  " + authUrl);
        openBrowser(authUrl);

        String code = waitForCallback(state);

        System.out.println("Authorization code received. Exchanging for tokens...");
        TokenInfo tokens = exchangeCode(code);
        configService.saveTokens(tokens);
        System.out.println("Login successful. Tokens saved to ~/.mycourses/tokens.json");
        return tokens;
    }

    /**
     * Refreshes the access token using the stored refresh token.
     * Called automatically by getValidAccessToken() when the token is about to expire.
     */
    public TokenInfo refresh(TokenInfo current) throws IOException {
        if (current.getRefreshToken() == null) {
            throw new IOException("No refresh token available. Run `mycourses login` again.");
        }
        RequestBody body = new FormBody.Builder()
            .add("grant_type",    "refresh_token")
            .add("refresh_token", current.getRefreshToken())
            .add("client_id",     config.getClientId())
            .build();

        Request req = new Request.Builder()
            .url(config.getTenantUrl() + TOKEN_PATH)
            .post(body)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Token refresh failed: HTTP " + resp.code());
            }
            TokenInfo fresh = parseTokenResponse(resp.body().string());
            configService.saveTokens(fresh);
            return fresh;
        }
    }

    /**
     * Returns a valid (non-expired) access token, refreshing automatically if needed.
     * Only used for OAuth Bearer flow; call addAuthHeader() for auth-method-agnostic code.
     */
    public String getValidAccessToken() throws Exception {
        TokenInfo tokens = configService.loadTokens();
        if (tokens.isExpired()) {
            System.out.println("Access token expired — refreshing...");
            tokens = refresh(tokens);
        }
        return tokens.getAccessToken();
    }

    /**
     * Saves browser session cookies collected by `mycourses auth`.
     * Clears any existing OAuth Bearer token fields so there is no ambiguity.
     */
    public void saveCookies(String sessionVal, String authCookieVal) throws IOException {
        TokenInfo t = new TokenInfo();
        t.setSessionVal(sessionVal);
        t.setAuthCookieVal(authCookieVal);
        configService.saveTokens(t);
    }

    /**
     * Adds the appropriate Authorization or Cookie header to a request builder.
     * Dispatches between cookie-based auth (`mycourses auth`) and
     * OAuth Bearer auth (`mycourses login`) based on what is stored in tokens.json.
     */
    public void addAuthHeader(Request.Builder builder) throws Exception {
        TokenInfo tokens = configService.loadTokens();
        if (tokens.useCookieAuth()) {
            String cookieHeader = "d2lSessionVal=" + tokens.getSessionVal();
            if (tokens.getAuthCookieVal() != null && !tokens.getAuthCookieVal().isBlank()) {
                cookieHeader += "; d2lSecureSessionVal=" + tokens.getAuthCookieVal();
            }
            builder.header("Cookie", cookieHeader);
        } else {
            builder.header("Authorization", "Bearer " + getValidAccessToken());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildAuthUrl(String state) throws UnsupportedEncodingException {
        return config.getTenantUrl() + AUTH_PATH
            + "?response_type=code"
            + "&client_id="     + URLEncoder.encode(config.getClientId(), "UTF-8")
            + "&redirect_uri="  + URLEncoder.encode(REDIRECT_URI, "UTF-8")
            + "&scope="         + URLEncoder.encode(SCOPES, "UTF-8")
            + "&state="         + state;
    }

    /** Starts a one-shot local HTTP server; blocks until the callback arrives (5-min timeout). */
    private String waitForCallback(String expectedState) throws Exception {
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
        server.createContext("/callback", ex -> handleCallback(ex, expectedState, codeFuture));
        server.start();
        try {
            return codeFuture.get(5, TimeUnit.MINUTES);
        } finally {
            server.stop(0);
        }
    }

    private void handleCallback(HttpExchange exchange, String expectedState,
                                CompletableFuture<String> codeFuture) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String html;

        if (params.containsKey("error")) {
            String err = params.get("error");
            html = "<h2>Login failed: " + err + "</h2><p>You may close this tab.</p>";
            codeFuture.completeExceptionally(new IOException("OAuth error: " + err));
        } else if (!expectedState.equals(params.get("state"))) {
            html = "<h2>State mismatch — possible CSRF attack. Close this tab.</h2>";
            codeFuture.completeExceptionally(new IOException("OAuth state mismatch"));
        } else {
            html = "<h2>Login successful!</h2><p>You may close this tab and return to the terminal.</p>";
            codeFuture.complete(params.get("code"));
        }

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private TokenInfo exchangeCode(String code) throws IOException {
        RequestBody body = new FormBody.Builder()
            .add("grant_type",   "authorization_code")
            .add("code",         code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id",    config.getClientId())
            .build();

        Request req = new Request.Builder()
            .url(config.getTenantUrl() + TOKEN_PATH)
            .post(body)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String body2 = resp.body() != null ? resp.body().string() : "";
                throw new IOException("Token exchange failed: HTTP " + resp.code() + " — " + body2);
            }
            return parseTokenResponse(resp.body().string());
        }
    }

    private TokenInfo parseTokenResponse(String json) throws IOException {
        JsonNode node = mapper.readTree(json);
        TokenInfo t = new TokenInfo();
        t.setAccessToken(node.get("access_token").asText());
        t.setTokenType(node.path("token_type").asText("Bearer"));
        if (node.has("refresh_token")) t.setRefreshToken(node.get("refresh_token").asText());
        long expiresIn = node.path("expires_in").asLong(3600);
        t.setExpiresAt(System.currentTimeMillis() / 1000 + expiresIn);
        if (node.has("scope")) t.setScope(node.get("scope").asText());
        return t;
    }

    private static String randomState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                } catch (UnsupportedEncodingException ignored) {}
            }
        }
        return map;
    }

    /** Best-effort browser open; falls back gracefully — user is shown the URL anyway. */
    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if      (os.contains("mac"))  Runtime.getRuntime().exec(new String[]{"open", url});
            else if (os.contains("linux"))Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            else if (os.contains("win"))  Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
        } catch (IOException ignored) {}
    }
}
