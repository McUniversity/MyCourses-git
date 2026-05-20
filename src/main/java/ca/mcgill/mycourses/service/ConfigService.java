package ca.mcgill.mycourses.service;

import ca.mcgill.mycourses.model.AppConfig;
import ca.mcgill.mycourses.model.TokenInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public class ConfigService {

    private static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".mycourses");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Path TOKEN_FILE  = CONFIG_DIR.resolve("tokens.json");

    private final ObjectMapper mapper;

    public ConfigService() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void ensureConfigDir() throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
            // Restrict to owner-only access (rwx------) on POSIX systems
            try {
                Files.setPosixFilePermissions(CONFIG_DIR, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) { /* Windows */ }
        }
    }

    public boolean isInitialized() {
        return Files.exists(CONFIG_FILE);
    }

    public AppConfig loadConfig() throws IOException {
        if (!Files.exists(CONFIG_FILE)) {
            throw new IllegalStateException("Not initialized. Run `mycourses init` first.");
        }
        return mapper.readValue(CONFIG_FILE.toFile(), AppConfig.class);
    }

    public void saveConfig(AppConfig config) throws IOException {
        ensureConfigDir();
        mapper.writeValue(CONFIG_FILE.toFile(), config);
    }

    public TokenInfo loadTokens() throws IOException {
        if (!Files.exists(TOKEN_FILE)) {
            throw new IllegalStateException("Not logged in. Run `mycourses login` first.");
        }
        return mapper.readValue(TOKEN_FILE.toFile(), TokenInfo.class);
    }

    public void saveTokens(TokenInfo tokens) throws IOException {
        ensureConfigDir();
        mapper.writeValue(TOKEN_FILE.toFile(), tokens);
        // Token file: owner read/write only (rw-------)
        try {
            Files.setPosixFilePermissions(TOKEN_FILE, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {}
    }

    public boolean hasTokens() {
        return Files.exists(TOKEN_FILE);
    }

    public Path getConfigDir() {
        return CONFIG_DIR;
    }
}
