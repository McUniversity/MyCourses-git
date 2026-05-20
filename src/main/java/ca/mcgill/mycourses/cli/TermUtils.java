package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.Course;

/** Helpers for extracting and matching D2L term codes from course codes. */
class TermUtils {

    private TermUtils() {}

    /**
     * Extracts a 6-digit term code (YYYYMM) from a D2L course code.
     * Handles both dot-separated ("1835.202601") and embedded ("XLS9P202509") formats.
     * Returns "" if no term code is found.
     */
    static String extractTermCode(String code) {
        if (code == null) return "";
        // Prefer the part after the last dot
        int dot = code.lastIndexOf('.');
        if (dot >= 0) {
            String suffix = code.substring(dot + 1);
            if (suffix.matches("\\d{6}")) return suffix;
        }
        // Fallback: last 6 characters if they look like YYYYMM
        if (code.length() >= 6) {
            String tail = code.substring(code.length() - 6);
            if (tail.matches("\\d{6}")) return tail;
        }
        return "";
    }

    /**
     * Returns a human-readable term label.
     * "202601" → "Winter 2026", "202509" → "Fall 2025", "202605" → "Summer 2026"
     */
    static String termLabel(String termCode) {
        if (termCode.length() < 6) return termCode.isEmpty() ? "Other" : termCode;
        try {
            int year  = Integer.parseInt(termCode.substring(0, 4));
            int month = Integer.parseInt(termCode.substring(4, 6));
            String season = switch (month) {
                case 1  -> "Winter";
                case 5  -> "Summer";
                case 9  -> "Fall";
                default -> "Term-" + month;
            };
            return season + " " + year;
        } catch (NumberFormatException e) {
            return termCode;
        }
    }

    /**
     * Returns true if the given term code matches the user's filter string.
     *
     * Supported filter formats (case-insensitive):
     *   "202601"        exact code
     *   "winter2026"    season + year
     *   "winter"        season only (matches any year)
     *   "w2026"/"w26"   abbreviated winter
     *   "f2025"/"f25"   abbreviated fall
     *   "s2026"/"s26"   abbreviated summer
     */
    static boolean matchesTerm(String termCode, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.toLowerCase().trim().replace(" ", "");

        // Exact code
        if (termCode.equalsIgnoreCase(f)) return true;

        String label = termLabel(termCode).toLowerCase().replace(" ", ""); // "winter2026"

        // Full label match ("winter2026")
        if (label.equals(f)) return true;

        // Season-only match ("winter" matches "winter2026", "winter2025", …)
        for (String season : new String[]{"winter", "fall", "summer"}) {
            if (f.equals(season) && label.startsWith(season)) return true;
        }

        // Abbreviated: "w2026", "w26", "f25", "s2026"
        if (termCode.length() == 6) {
            try {
                int year  = Integer.parseInt(termCode.substring(0, 4));
                int month = Integer.parseInt(termCode.substring(4, 6));
                String abbr = switch (month) { case 1 -> "w"; case 9 -> "f"; default -> "s"; };
                String shortYear = termCode.substring(2, 4);
                if (f.equals(abbr + year) || f.equals(abbr + shortYear)) return true;
            } catch (NumberFormatException ignored) {}
        }

        return false;
    }

    /**
     * Returns true if the course name or code contains the filter string.
     * Strips spaces and hyphens before comparing so "COMP273", "comp 273",
     * and "COMP-273" all match "COMP 273 - Introduction to Computer Systems".
     */
    static boolean matchesCourse(Course course, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String f    = filter.toLowerCase().replace(" ", "").replace("-", "");
        String name = course.getName().toLowerCase().replace(" ", "").replace("-", "");
        String code = course.getCode().toLowerCase().replace(" ", "").replace("-", "");
        return name.contains(f) || code.contains(f);
    }
}
