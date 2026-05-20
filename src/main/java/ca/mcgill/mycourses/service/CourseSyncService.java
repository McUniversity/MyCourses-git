package ca.mcgill.mycourses.service;

import ca.mcgill.mycourses.model.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.List;

/**
 * Orchestrates fetching content from the Brightspace API and writing it locally.
 * Each syncCourse() call is independent; partial failures are logged and skipped
 * so one broken course does not abort the entire pull.
 */
public class CourseSyncService {

    private final BrightspaceClient api;
    private final FileStorageService storage;

    public CourseSyncService(BrightspaceClient api, FileStorageService storage) {
        this.api     = api;
        this.storage = storage;
    }

    public void syncCourse(Course course) {
        String label = course.getCode().isBlank() ? course.getName() : course.getCode();
        System.out.println("\nSyncing " + label + " (id=" + course.getOrgUnitId() + ")...");

        trySync("[course-info]", () -> {
            storage.initCourseDir(course);
            storage.writeCourseInfo(course);
        });

        trySync("[announcements]", () -> {
            List<Announcement> items = api.getAnnouncements(course.getOrgUnitId());
            storage.writeAnnouncements(course, items);
            System.out.println("  [announcements] " + items.size() + " item(s)");
        });

        trySync("[assignments]", () -> {
            List<Assignment> items = api.getAssignments(course.getOrgUnitId());
            storage.writeAssignments(course, items);
            System.out.println("  [assignments] " + items.size() + " item(s)");
        });

        trySync("[content]", () -> {
            JsonNode toc = api.getContentToc(course.getOrgUnitId());
            storage.writeContentToc(course, toc);
            System.out.println("  [content-toc] saved");
            downloadTopicsFromToc(course, toc);
        });
    }

    // -------------------------------------------------------------------------
    // Recursive TOC traversal + file download
    // -------------------------------------------------------------------------

    /**
     * Walks the Brightspace content TOC tree, downloading every file topic.
     *
     * Brightspace TOC JSON shape (simplified):
     * {
     *   "Modules": [{
     *     "Title": "Week 1 — Sorting",
     *     "Topics": [{
     *       "TopicType": 1,        // 1=File  3=Link  (skip non-1)
     *       "Identifier": "98765",
     *       "Title": "Lecture01.pdf"
     *     }],
     *     "Modules": [...]          // nested sub-modules — recurse
     *   }]
     * }
     */
    private void downloadTopicsFromToc(Course course, JsonNode node) {
        if (node == null) return;
        // Root-level topics (rare but possible)
        processTopics(course, "", node.path("Topics"));
        // Module topics (recursive)
        for (JsonNode module : node.path("Modules")) {
            String moduleTitle = module.path("Title").asText("");
            processTopics(course, moduleTitle, module.path("Topics"));
            downloadTopicsFromToc(course, module);
        }
    }

    private void processTopics(Course course, String moduleTitle, JsonNode topics) {
        for (JsonNode topic : topics) {
            if (topic.path("TopicType").asInt(0) != 1) continue; // files only

            long topicId;
            try {
                topicId = Long.parseLong(topic.path("Identifier").asText("0"));
            } catch (NumberFormatException e) { continue; }

            String title = topic.path("Title").asText("file_" + topicId);
            trySync("[file] " + title, () -> {
                InputStream data = api.downloadFile(course.getOrgUnitId(), topicId);
                storage.saveFile(course, moduleTitle, title, data);
            });
        }
    }

    // -------------------------------------------------------------------------

    @FunctionalInterface
    interface SyncTask { void run() throws Exception; }

    private void trySync(String label, SyncTask task) {
        try {
            task.run();
        } catch (Exception e) {
            System.err.println("  " + label + " ERROR: " + e.getMessage());
        }
    }
}
