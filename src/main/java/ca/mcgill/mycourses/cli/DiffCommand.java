package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.service.ConfigService;
import ca.mcgill.mycourses.service.GitService;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "diff",
    mixinStandardHelpOptions = true,
    description = "Show uncommitted changes as a unified diff."
)
public class DiffCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        ConfigService cs     = new ConfigService();
        AppConfig     config = cs.loadConfig();
        Path          base   = Path.of(config.getBaseDir());

        try (GitService git = GitService.open(base)) {
            System.out.println(git.diff());
        }
        return 0;
    }
}
