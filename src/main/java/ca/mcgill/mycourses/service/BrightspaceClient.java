package ca.mcgill.mycourses.service;

import ca.mcgill.mycourses.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Thin, read-only HTTP client for the Brightspace / D2L Valence REST API.
 *
 * Official API reference: https://docs.valence.desire2learn.com/reference.html
 * McGill tenant:          https://mycourses2.mcgill.ca
 *
 * All calls are GET-only; nothing is ever written back to myCourses.
 *
 * NOTE: Brightspace API access requires a client_id registered by McGill IT.
 *       If self-service registration is unavailable, see the README for a
 *       manual-export fallback that does not require API access at all.
 */
public class BrightspaceClient {

    private final AppConfig config;
    private final AuthService authService;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public BrightspaceClient(AppConfig config, AuthService authService) {
        this.config      = config;
        this.authService = authService;
        this.http        = new OkHttpClient();
        this.mapper      = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // -------------------------------------------------------------------------
    // Enrollments
    // GET /d2l/api/lp/{lpVersion}/enrollments/myenrollments/?orgUnitTypeId=3
    //   orgUnitTypeId=3 filters to Course Offerings only (excludes departments, templates).
    //   Response is paged; we follow Bookmark-based pagination until exhausted.
    // -------------------------------------------------------------------------
    public List<Course> getEnrolledCourses() throws Exception {
        String baseUrl = lpUrl("/enrollments/myenrollments/?orgUnitTypeId=3");
        List<Course> courses = new ArrayList<>();
        String bookmark = null;

        do {
            String url = bookmark != null ? baseUrl + "&bookmark=" + bookmark : baseUrl;
            JsonNode root = getJson(url);

            for (JsonNode item : root.path("Items")) {
                JsonNode ou = item.path("OrgUnit");
                Course c = new Course();
                c.setOrgUnitId(ou.path("Id").asLong());
                c.setName(ou.path("Name").asText());
                c.setCode(ou.path("Code").asText());
                c.setActive(item.path("Access").path("IsActive").asBoolean(true));
                courses.add(c);
            }

            JsonNode paging = root.path("PagingInfo");
            bookmark = paging.path("HasMoreItems").asBoolean(false)
                ? paging.path("Bookmark").asText(null) : null;
        } while (bookmark != null);

        return courses;
    }

    // -------------------------------------------------------------------------
    // API version discovery
    // GET /d2l/api/versions/  (no auth required)
    //   Returns supported LP and LE versions for this tenant. We use the latest
    //   supported versions to avoid 404s from version mismatches.
    // -------------------------------------------------------------------------
    public void discoverVersions() {
        try {
            Request req = new Request.Builder()
                .url(config.getTenantUrl() + "/d2l/api/versions/")
                .header("Accept", "application/json")
                .get()
                .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return;
                JsonNode versions = mapper.readTree(resp.body().string());
                for (JsonNode v : versions) {
                    String code   = v.path("ProductCode").asText("");
                    String latest = v.path("LatestVersion").asText("");
                    if (latest.isBlank()) continue;
                    if ("lp".equalsIgnoreCase(code)) config.setLpVersion(latest);
                    if ("le".equalsIgnoreCase(code)) config.setLeVersion(latest);
                }
            }
            System.out.println("API versions: lp=" + config.getLpVersion()
                + "  le=" + config.getLeVersion());
        } catch (Exception ignored) {
            // Best-effort — defaults in config.json still apply
        }
    }

    // -------------------------------------------------------------------------
    // Content tree
    // GET /d2l/api/le/{leVersion}/{orgUnitId}/content/root/
    //   Returns an array of top-level modules. Each module has a Structure array
    //   whose items are either topics (Type=1) or sub-modules (Type=0).
    //   We normalise this into {Modules:[{Title, Topics:[{TopicType,Identifier,Title}],
    //   Modules:[...]}]} so the existing traversal code in CourseSyncService works
    //   without changes.
    //
    //   Note: /content/toc/ exists but returns empty Topics on McGill's instance;
    //   /content/root/ is the reliable endpoint.
    // -------------------------------------------------------------------------
    public JsonNode getContentToc(long orgUnitId) throws Exception {
        JsonNode root = getJson(leUrl(orgUnitId, "/content/root/"));
        return normalizeRootToToc(root);
    }

    private JsonNode normalizeRootToToc(JsonNode rootArray) {
        ObjectNode toc = mapper.createObjectNode();
        ArrayNode modules = mapper.createArrayNode();
        if (rootArray.isArray()) {
            for (JsonNode item : rootArray) {
                if (item.path("Type").asInt(-1) == 0) {
                    modules.add(normalizeStructureItem(item));
                }
            }
        }
        toc.set("Modules", modules);
        return toc;
    }

    /**
     * Converts a /content/root/ Structure item into the TOC module shape.
     *
     * /content/root/ structure:
     *   { Id, Title, Type(0=module/1=topic), Structure:[{Id, Title, Type, ...}] }
     *
     * TOC shape expected by CourseSyncService:
     *   { Title, Topics:[{Identifier, Title, TopicType}], Modules:[...] }
     */
    private ObjectNode normalizeStructureItem(JsonNode item) {
        ObjectNode out = mapper.createObjectNode();
        out.put("Title", item.path("Title").asText(""));

        ArrayNode topics = mapper.createArrayNode();
        ArrayNode subModules = mapper.createArrayNode();

        for (JsonNode child : item.path("Structure")) {
            int type = child.path("Type").asInt(-1);
            if (type == 1) {
                ObjectNode topic = mapper.createObjectNode();
                topic.put("Title", child.path("Title").asText("file_" + child.path("Id").asLong()));
                topic.put("TopicType", 1);
                topic.put("Identifier", String.valueOf(child.path("Id").asLong(0)));
                topics.add(topic);
            } else if (type == 0) {
                // Sub-module: Structure is not populated here; record it for recursion.
                // A deeper fetch via /content/modules/{id}/structure/ would be needed
                // to get its topics — skipped for now, child modules appear empty.
                subModules.add(normalizeStructureItem(child));
            }
        }

        out.set("Topics", topics);
        out.set("Modules", subModules);
        return out;
    }

    // -------------------------------------------------------------------------
    // Announcements (News)
    // GET /d2l/api/le/{leVersion}/{orgUnitId}/news/
    //   Returns course announcements as a paged result set.
    //   Body.Html contains the HTML-formatted announcement text.
    // -------------------------------------------------------------------------
    public List<Announcement> getAnnouncements(long orgUnitId) throws Exception {
        JsonNode root = getJson(leUrl(orgUnitId, "/news/"));
        List<Announcement> list = new ArrayList<>();
        // D2L news endpoint returns either an array or a paged { Objects: [...] } wrapper
        JsonNode items = root.isArray() ? root : root.path("Objects");
        for (JsonNode item : items) {
            Announcement a = new Announcement();
            a.setId(item.path("Id").asLong());
            a.setTitle(item.path("Title").asText());
            a.setBody(item.path("Body").path("Html").asText(item.path("Body").asText("")));
            String date = item.path("StartDate").asText(null);
            if (date != null) {
                try { a.setStartDate(java.time.Instant.parse(date)); }
                catch (Exception ignored) {}
            }
            a.setAuthorName(item.path("CreatedBy").path("DisplayName").asText(""));
            list.add(a);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Assignments (Dropbox folders — metadata only, no student submissions)
    // GET /d2l/api/le/{leVersion}/{orgUnitId}/dropbox/folders/
    //   Returns assignment definitions: name, due date, instructions, point value.
    //   No student submissions or grades are accessed.
    // -------------------------------------------------------------------------
    public List<Assignment> getAssignments(long orgUnitId) throws Exception {
        JsonNode root = getJson(leUrl(orgUnitId, "/dropbox/folders/"));
        List<Assignment> list = new ArrayList<>();
        JsonNode items = root.isArray() ? root : root.path("Objects");
        for (JsonNode item : items) {
            Assignment a = new Assignment();
            a.setId(item.path("Id").asLong());
            a.setName(item.path("Name").asText());
            a.setInstructions(item.path("CustomInstructions").path("Html").asText(""));
            String dueDate = item.path("DueDate").asText(null);
            if (dueDate != null) {
                try { a.setDueDate(java.time.Instant.parse(dueDate)); }
                catch (Exception ignored) {}
            }
            a.setTotalPoints((float) item.path("TotalPoints").asDouble(0));
            list.add(a);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // File download
    // GET /d2l/api/le/{leVersion}/{orgUnitId}/content/topics/{topicId}/file
    //   Downloads the raw file bytes for a given content topic.
    //   Caller is responsible for closing the returned InputStream.
    // -------------------------------------------------------------------------
    public InputStream downloadFile(long orgUnitId, long topicId) throws Exception {
        String url = leUrl(orgUnitId, "/content/topics/" + topicId + "/file");
        Request.Builder builder = new Request.Builder().url(url).get();
        authService.addAuthHeader(builder);
        Response resp = http.newCall(builder.build()).execute();
        if (!resp.isSuccessful()) {
            resp.close();
            throw new IOException("File download HTTP " + resp.code() + " (topicId=" + topicId + ")");
        }
        return resp.body().byteStream(); // caller closes
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private JsonNode getJson(String url) throws Exception {
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get();
        authService.addAuthHeader(builder);
        try (Response resp = http.newCall(builder.build()).execute()) {
            if (resp.code() == 401) throw new IOException(
                "Unauthorized (401). Re-run `mycourses auth` to refresh your session.");
            if (resp.code() == 403) throw new IOException(
                "Forbidden (403) for " + url + ". Your session may not have access to this resource.");
            if (!resp.isSuccessful()) throw new IOException(
                "HTTP " + resp.code() + " for " + url);
            return mapper.readTree(resp.body().string());
        }
    }

    /** Constructs a Learning Platform (LP) API URL. */
    private String lpUrl(String path) {
        return config.getTenantUrl() + "/d2l/api/lp/" + config.getLpVersion() + path;
    }

    /** Constructs a Learning Environment (LE) API URL for a specific org unit. */
    private String leUrl(long orgUnitId, String path) {
        return config.getTenantUrl() + "/d2l/api/le/" + config.getLeVersion()
            + "/" + orgUnitId + path;
    }
}
