package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.service.ConfigService;
import ca.mcgill.mycourses.service.GitService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.util.concurrent.Callable;

@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = "Initialize the local sync directory and Git repository."
)
public class InitCommand implements Callable<Integer> {

    @Option(names = {"-d", "--dir"},
            description = "Base directory for course files (default: ~/mcgill-courses)")
    private String dir;

    @Option(names = {"--tenant"},
            description = "Brightspace tenant URL (default: https://mycourses2.mcgill.ca)")
    private String tenant;

    @Option(names = {"--client-id"},
            description = "OAuth2 client_id registered with McGill Brightspace admin")
    private String clientId;

    @Override
    public Integer call() throws Exception {
        ConfigService cs = new ConfigService();
        cs.ensureConfigDir();

        AppConfig config = new AppConfig();

        Path base = dir != null
            ? Path.of(dir).toAbsolutePath()
            : Path.of(System.getProperty("user.home"), "mcgill-courses");
        config.setBaseDir(base.toString());

        if (tenant   != null) config.setTenantUrl(tenant);
        if (clientId != null) config.setClientId(clientId);

        Files.createDirectories(base);

        try (GitService git = GitService.open(base)) {
            System.out.println("Git repository ready at " + base);
        }

        cs.saveConfig(config);

        System.out.println("Initialized mycourses-sync.");
        System.out.println("  Config : ~/.mycourses/config.json");
        System.out.println("  Courses: " + base);

        if (cs.hasTokens()) {
            System.out.println("Next step: mycourses pull");
        } else {
            System.out.println("Next step: mycourses auth");
        }
        return 0;
    }
}
