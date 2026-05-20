package ca.mcgill.mycourses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    /** Brightspace tenant URL — McGill's instance is https://mycourses2.mcgill.ca */
    private String tenantUrl = "https://mycourses2.mcgill.ca";
    /** OAuth2 client_id registered via Brightspace Admin Tools > OAuth 2.0 */
    private String clientId;
    /** Absolute path to the local course sync directory */
    private String baseDir;
    /** D2L Learning Platform API version */
    private String lpVersion = "1.28";
    /** D2L Learning Environment (LE) API version */
    private String leVersion = "1.67";

    public AppConfig() {}

    public String getTenantUrl() { return tenantUrl; }
    public void setTenantUrl(String tenantUrl) { this.tenantUrl = tenantUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public String getLpVersion() { return lpVersion; }
    public void setLpVersion(String lpVersion) { this.lpVersion = lpVersion; }
    public String getLeVersion() { return leVersion; }
    public void setLeVersion(String leVersion) { this.leVersion = leVersion; }
}
