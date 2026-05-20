package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.*;
import ca.mcgill.mycourses.service.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "pull",
    mixinStandardHelpOptions = true,
    description = {
        "Download/update course content from myCourses and commit changes locally.",
        "",
        "Examples:",
        "  pull                           sync all active courses",
        "  pull --term winter2026         sync all Winter 2026 courses",
        "  pull COMP273                   sync any course whose name/code contains 'COMP273'",
        "  pull --term winter2026 COMP273 sync COMP 273 in Winter 2026 only"
    }
)
public class PullCommand implements Callable<Integer> {

    @Option(names = {"-t", "--term"},
            description = "Filter by term: season+year (winter2026, fall2025), "
                        + "abbreviated (w2026, f25), season only (winter), or D2L code (202601).")
    private String term;

    @Parameters(
        index = "0",
        arity = "0..1",
        description = "Filter by course name or code (case-insensitive, partial match). "
                    + "E.g. COMP273, comp, 1835."
    )
    private String courseFilter;

    @Override
    public Integer call() throws Exception {
        ConfigService     cs      = new ConfigService();
        AppConfig         config  = cs.loadConfig();
        Path              baseDir = Path.of(config.getBaseDir());
        AuthService       auth    = new AuthService(config, cs);
        BrightspaceClient api     = new BrightspaceClient(config, auth);
        FileStorageService fs     = new FileStorageService(baseDir);
        CourseSyncService  sync   = new CourseSyncService(api, fs);

        api.discoverVersions();

        System.out.println("Fetching enrolled courses...");
        List<Course> courses = api.getEnrolledCourses();

        // Apply filters
        List<Course> selected = courses.stream()
            .filter(Course::isActive)
            .filter(c -> TermUtils.matchesTerm(TermUtils.extractTermCode(c.getCode()), term))
            .filter(c -> TermUtils.matchesCourse(c, courseFilter))
            .toList();

        if (selected.isEmpty()) {
            System.err.println("No active courses matched your filters.");
            if (term != null)         System.err.println("  --term      : " + term);
            if (courseFilter != null) System.err.println("  course filter: " + courseFilter);
            System.err.println("Run `mycourses list` to see available courses.");
            return 1;
        }

        System.out.println("Syncing " + selected.size() + " course(s):");
        for (Course c : selected) {
            String tc = TermUtils.extractTermCode(c.getCode());
            System.out.println("  " + c.getCode() + "  " + c.getName()
                + (tc.isEmpty() ? "" : "  [" + TermUtils.termLabel(tc) + "]"));
        }
        System.out.println();

        for (Course course : selected) {
            sync.syncCourse(course);
        }

        try (GitService git = GitService.open(baseDir)) {
            String sha = git.commitAll(GitService.syncMessage());
            if (sha != null) {
                System.out.println("\n[" + sha + "] " + GitService.syncMessage());
            }
        }
        return 0;
    }
}
