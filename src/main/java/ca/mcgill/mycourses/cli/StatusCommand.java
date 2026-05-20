package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.service.ConfigService;
import ca.mcgill.mycourses.service.GitService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = "Show working-tree state and recent sync commits."
)
public class StatusCommand implements Callable<Integer> {

    @Option(names = {"-n", "--count"}, description = "Number of recent commits to show (default: 10)")
    private int count = 10;

    @Override
    public Integer call() throws Exception {
        ConfigService cs     = new ConfigService();
        AppConfig     config = cs.loadConfig();
        Path          base   = Path.of(config.getBaseDir());

        try (GitService git = GitService.open(base)) {
            System.out.println("=== Working Tree Status ===");
            System.out.print(git.status());
            System.out.println();
            System.out.println("=== Recent Commits ===");
            System.out.print(git.log(count));
        }
        return 0;
    }
}
