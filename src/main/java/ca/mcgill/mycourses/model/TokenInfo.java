package ca.mcgill.mycourses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenInfo {
    // --- OAuth Bearer token fields (set by `mycourses login`) ---
    private String accessToken;
    private String refreshToken;
    /** Unix epoch seconds when the access token expires */
    private long expiresAt;
    private String tokenType;
    private String scope;

    // --- Browser session cookie fields (set by `mycourses auth`) ---
    private String sessionVal;      // d2l_sessionVal cookie
    private String authCookieVal;   // d2l_authCookieVal cookie

    public TokenInfo() {}

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getSessionVal() { return sessionVal; }
    public void setSessionVal(String sessionVal) { this.sessionVal = sessionVal; }
    public String getAuthCookieVal() { return authCookieVal; }
    public void setAuthCookieVal(String authCookieVal) { this.authCookieVal = authCookieVal; }

    /** True if session cookies are present (cookie-based auth via `mycourses auth`). */
    public boolean useCookieAuth() {
        return sessionVal != null && !sessionVal.isBlank();
    }

    /** True if the OAuth access token has expired or will expire within 60 seconds. */
    public boolean isExpired() {
        return System.currentTimeMillis() / 1000 >= expiresAt - 60;
    }
}
