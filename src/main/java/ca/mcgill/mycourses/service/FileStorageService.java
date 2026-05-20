package ca.mcgill.mycourses.service;

import ca.mcgill.mycourses.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes course content to the local filesystem.
 *
 * Output layout under baseDir:
 *
 *   {COURSE_CODE}/
 *     course-info.json        — course metadata
 *     announcements.md        — all announcements rendered as Markdown
 *     assignments/            — one .json per assignment (metadata only)
 *     lectures/               — files from modules whose title contains "lecture"
 *     content/                — all other downloaded files + toc.json
 */
public class FileStorageService {

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileStorageService(Path baseDir) {
        this.baseDir = baseDir;
        this.mapper  = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule());
    }

    public Path courseDir(Course course) {
        String raw = course.getName().isBlank() ? course.getCode() : course.getName();
        return baseDir.resolve(folderName(raw));
    }

    /**
     * Derives a clean folder name from a D2L course name.
     * "COMP 273 - Introduction to Computer Systems Winter 2026" →
     * "COMP 273 - Introduction to Computer Systems"
     */
    static String folderName(String name) {
        // Strip trailing term label added by D2L ("Winter 2026", "Fall 2025", etc.)
        String clean = name.replaceAll("(?i)\\s+(Winter|Fall|Summer)\\s+20\\d{2}\\s*$", "").trim();
        // Remove filesystem-illegal characters
        clean = clean.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        // Collapse runs of whitespace
        clean = clean.replaceAll("\\s{2,}", " ");
        return clean;
    }

    public void initCourseDir(Course course) throws IOException {
        Path dir = courseDir(course);
        Files.createDirectories(dir.resolve("lectures"));
        Files.createDirectories(dir.resolve("assignments"));
        Files.createDirectories(dir.resolve("content"));
    }

    public void writeCourseInfo(Course course) throws IOException {
        mapper.writeValue(courseDir(course).resolve("course-info.json").toFile(), course);
    }

    /**
     * Writes all announcements as a single Markdown file (overwritten on each sync).
     * HTML in announcement bodies is stripped to plain text.
     */
    public void writeAnnouncements(Course course, List<Announcement> announcements) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Announcements — ").append(course.getCode()).append("\n\n");
        for (Announcement a : announcements) {
            sb.append("## ").append(a.getTitle()).append("\n");
            if (a.getStartDate() != null) {
                sb.append("**Date:** ")
                  .append(DateTimeFormatter.ISO_INSTANT.format(a.getStartDate()))
                  .append("\n");
            }
            if (a.getAuthorName() != null && !a.getAuthorName().isBlank()) {
                sb.append("**Author:** ").append(a.getAuthorName()).append("\n");
            }
            sb.append("\n").append(stripHtml(a.getBody())).append("\n\n---\n\n");
        }
        Files.writeString(
            courseDir(course).resolve("announcements.md"),
            sb.toString(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /**
     * Saves each assignment as a separate JSON file in assignments/.
     * Only metadata (name, due date, instructions, points) is stored — no submissions.
     */
    public void writeAssignments(Course course, List<Assignment> assignments) throws IOException {
        Path dir = courseDir(course).resolve("assignments");
        for (Assignment a : assignments) {
            mapper.writeValue(dir.resolve(sanitize(a.getName()) + ".json").toFile(), a);
        }
    }

    /** Saves the raw content TOC JSON returned by the Brightspace API. */
    public void writeContentToc(Course course, JsonNode toc) throws IOException {
        mapper.writeValue(courseDir(course).resolve("content").resolve("toc.json").toFile(), toc);
    }

    /**
     * Saves a downloaded file into lectures/ or content/ based on the module title.
     * Files from modules whose title contains "lecture" go into lectures/.
     */
    public void saveFile(Course course, String moduleTitle, String filename,
                         InputStream data) throws IOException {
        String folder = moduleTitle != null && moduleTitle.toLowerCase().contains("lecture")
            ? "lectures" : "content";
        Path target = courseDir(course).resolve(folder).resolve(sanitize(filename));
        try (InputStream in = data) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("  saved: " + courseDir(course).relativize(target));
    }

    /** Strips HTML tags and decodes common HTML entities for plain-text rendering. */
    public static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                   .replace("&amp;", "&").replace("&lt;", "<")
                   .replace("&gt;", ">").replace("&nbsp;", " ").trim();
    }

    /** Replaces characters illegal in most filesystems with underscores. */
    public static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
