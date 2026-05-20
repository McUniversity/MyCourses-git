package ca.mcgill.mycourses.cli;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.service.AuthService;
import ca.mcgill.mycourses.service.ConfigService;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
    name = "login",
    mixinStandardHelpOptions = true,
    description = "Authenticate with McGill myCourses via OAuth 2.0 (opens browser)."
)
public class LoginCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        ConfigService cs     = new ConfigService();
        AppConfig     config = cs.loadConfig();
        AuthService   auth   = new AuthService(config, cs);

        System.out.println("Authenticating with " + config.getTenantUrl());
        auth.login();
        return 0;
    }
}
