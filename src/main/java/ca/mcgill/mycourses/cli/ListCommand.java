package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.model.Course;
import ca.mcgill.mycourses.service.AuthService;
import ca.mcgill.mycourses.service.BrightspaceClient;
import ca.mcgill.mycourses.service.ConfigService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.*;
import java.util.concurrent.Callable;

@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "List enrolled courses grouped by term."
)
public class ListCommand implements Callable<Integer> {

    @Option(names = {"-t", "--term"},
            description = "Filter by term (e.g. winter2026, fall2025, 202601)")
    private String term;

    @Override
    public Integer call() throws Exception {
        ConfigService     cs     = new ConfigService();
        AppConfig         config = cs.loadConfig();
        AuthService       auth   = new AuthService(config, cs);
        BrightspaceClient api    = new BrightspaceClient(config, auth);

        api.discoverVersions();
        List<Course> courses = api.getEnrolledCourses();

        // Group by term code, preserving insertion order (API returns most-recent first)
        Map<String, List<Course>> byTerm = new LinkedHashMap<>();
        for (Course c : courses) {
            String tc = TermUtils.extractTermCode(c.getCode());
            if (!TermUtils.matchesTerm(tc, term)) continue;
            byTerm.computeIfAbsent(tc, k -> new ArrayList<>()).add(c);
        }

        if (byTerm.isEmpty()) {
            System.out.println("No courses found" + (term != null ? " for term: " + term : "") + ".");
            return 0;
        }

        for (Map.Entry<String, List<Course>> entry : byTerm.entrySet()) {
            String tc    = entry.getKey();
            String label = TermUtils.termLabel(tc);
            System.out.println(label + (tc.isEmpty() ? "" : "  (" + tc + ")"));
            for (Course c : entry.getValue()) {
                System.out.printf("  %-22s  %s%n", c.getCode(), c.getName());
            }
            System.out.println();
        }

        System.out.println("Usage:");
        System.out.println("  mycourses pull --term winter2026");
        System.out.println("  mycourses pull COMP273");
        System.out.println("  mycourses pull --term winter2026 COMP273");
        return 0;
    }
}
