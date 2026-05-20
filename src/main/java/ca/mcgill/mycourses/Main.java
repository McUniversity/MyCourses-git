package ca.mcgill.mycourses;

import ca.mcgill.mycourses.cli.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "mycourses",
    mixinStandardHelpOptions = true,
    version = "1.0",
    description = "Read-only local sync tool for McGill myCourses (Brightspace/D2L).",
    subcommands = {
        InitCommand.class,
        AuthCommand.class,
        LoginCommand.class,
        ListCommand.class,
        PullCommand.class,
        StatusCommand.class,
        DiffCommand.class
    }
)
public class Main implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
