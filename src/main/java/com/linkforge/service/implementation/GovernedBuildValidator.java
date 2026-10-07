package com.linkforge.service.implementation;

import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Executes a fixed Maven Wrapper verification command inside an isolated workspace.
 * Strictly bounds execution duration and captured output, and strips all secrets/credentials
 * from the execution environment.
 */
@Component
public class GovernedBuildValidator {

    private static final Logger log = LoggerFactory.getLogger(GovernedBuildValidator.class);

    private static final Set<String> ALLOWED_ENV_VARS = Set.of(
            "PATH", "JAVA_HOME", "HOME", "USER", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL"
    );

    private final GovernedExecutionProperties properties;
    private final SurefireReportParser surefireReportParser;

    @Autowired
    public GovernedBuildValidator(
            GovernedExecutionProperties properties,
            @Autowired(required = false) SurefireReportParser surefireReportParser
    ) {
        this.properties = properties != null ? properties : new GovernedExecutionProperties();
        this.surefireReportParser = surefireReportParser != null ? surefireReportParser : new SurefireReportParser();
    }

    public GovernedBuildValidator(GovernedExecutionProperties properties) {
        this(properties, new SurefireReportParser());
    }

    public BuildValidationResult validateBuild(Path workspaceRoot) {
        return validateBuild(workspaceRoot, null);
    }

    public BuildValidationResult validateBuild(
            Path workspaceRoot,
            com.linkforge.domain.workflow.implementation.ImplementationProposal proposal
    ) {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace root must be an existing directory.");
        }

        Instant startedAt = Instant.now();
        String command = properties.getFixedBuildCommand();
        boolean fullVerification = command.contains("verify") || (!command.contains("-Dtest=") && command.contains("test"));

        try {
            ensureMavenWrapperPresent(workspaceRoot);

            List<String> commandTokens = prepareCommandTokens(command, workspaceRoot);
            ProcessBuilder processBuilder = new ProcessBuilder(commandTokens);
            processBuilder.directory(workspaceRoot.toFile());
            processBuilder.redirectErrorStream(true);

            // Strip environment secrets, tokens, API keys, and model credentials
            sanitizeEnvironment(processBuilder.environment());

            log.info("Starting governed build validation in isolated workspace {}: {}", workspaceRoot, commandTokens);
            Process process = processBuilder.start();

            // Drain output concurrently into a strictly bounded collector that handles
            // very long lines without unbounded memory use
            BoundedOutputDrainer drainer = new BoundedOutputDrainer(
                    process.getInputStream(),
                    properties.getMaxCapturedOutputChars()
            );
            Thread drainerThread = new Thread(drainer, "mvnw-bounded-drainer");
            drainerThread.setDaemon(true);
            drainerThread.start();

            boolean finished = process.waitFor(properties.getBuildTimeoutSeconds(), TimeUnit.SECONDS);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();

            if (!finished) {
                // When timeout expires, terminate child process and all its descendants
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (Exception ignored) {}
                process.destroyForcibly();
                drainer.stop();
                try {
                    drainerThread.join(500);
                } catch (InterruptedException ignored) {}

                log.warn("Governed build timed out after {} seconds.", properties.getBuildTimeoutSeconds());
                return new BuildValidationResult(
                        command,
                        -1,
                        durationMs,
                        drainer.getCapturedOutput() + "\nBuild execution timed out after " + properties.getBuildTimeoutSeconds() + " seconds.",
                        "TIMED_OUT",
                        Instant.now(),
                        fullVerification,
                        List.of()
                );
            }

            // Process finished normally: join drainer thread briefly
            try {
                drainerThread.join(1000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            List<com.linkforge.domain.workflow.implementation.TestReportItem> testReports = surefireReportParser.parseReports(workspaceRoot, proposal);
            boolean hasFailedTests = testReports.stream().anyMatch(com.linkforge.domain.workflow.implementation.TestReportItem::isFailed);
            String status = (exitCode == 0 && !hasFailedTests) ? "SUCCESS" : "BUILD_FAILED";

            log.info("Governed build validation completed with exit code {} in {}ms (status: {}, tests discovered: {})",
                    exitCode, durationMs, status, testReports.size());
            return new BuildValidationResult(
                    command,
                    exitCode,
                    durationMs,
                    drainer.getCapturedOutput(),
                    status,
                    Instant.now(),
                    fullVerification,
                    testReports
            );

        } catch (Exception ex) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            log.error("Error executing governed build validation: {}", ex.getMessage(), ex);
            return new BuildValidationResult(
                    command,
                    -1,
                    durationMs,
                    "Execution error: " + ex.getMessage(),
                    "ERROR",
                    Instant.now(),
                    fullVerification,
                    List.of()
            );
        }
    }

    private void sanitizeEnvironment(Map<String, String> env) {
        List<String> toRemove = new ArrayList<>();
        for (String key : env.keySet()) {
            String upper = key.toUpperCase(Locale.ROOT);
            if (!ALLOWED_ENV_VARS.contains(upper)) {
                toRemove.add(key);
            } else if (upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET")
                    || upper.contains("PASS") || upper.contains("CRED") || upper.contains("OLLAMA")
                    || upper.contains("AUTH")) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            env.remove(key);
        }
    }

    private List<String> prepareCommandTokens(String rawCommand, Path workspaceRoot) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path mvnwPath = workspaceRoot.resolve(isWindows ? "mvnw.cmd" : "mvnw");

        List<String> tokens = new ArrayList<>();
        if (isWindows) {
            tokens.add("cmd.exe");
            tokens.add("/c");
            tokens.add(mvnwPath.toAbsolutePath().toString());
        } else {
            tokens.add(mvnwPath.toAbsolutePath().toString());
        }

        // Parse arguments from rawCommand
        String[] parts = rawCommand.trim().split("\\s+");
        for (String part : parts) {
            if (part.equals("./mvnw") || part.equals("mvnw") || part.equals("mvnw.cmd") || part.isBlank()) {
                continue;
            }
            tokens.add(part);
        }

        if (!tokens.contains("--batch-mode")) {
            tokens.add(1, "--batch-mode");
        }

        return tokens;
    }

    /**
     * Drains child process stream concurrently into a bounded buffer.
     * Uses fixed-size char chunks to handle streams without newlines or extremely long lines
     * without unbounded heap memory usage.
     */
    public static class BoundedOutputDrainer implements Runnable {
        private final java.io.InputStream inputStream;
        private final int maxChars;
        private final StringBuilder output = new StringBuilder();
        private volatile boolean truncated = false;
        private volatile boolean stopped = false;
        private final Object lock = new Object();

        public BoundedOutputDrainer(java.io.InputStream inputStream, int maxChars) {
            this.inputStream = inputStream;
            this.maxChars = maxChars;
        }

        @Override
        public void run() {
            try (java.io.Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[512];
                int read;
                while (!stopped && (read = reader.read(buffer)) != -1) {
                    synchronized (lock) {
                        if (output.length() < maxChars) {
                            int remaining = maxChars - output.length();
                            int toAppend = Math.min(read, remaining);
                            output.append(buffer, 0, toAppend);
                            if (output.length() >= maxChars && !truncated) {
                                truncated = true;
                                output.append("\n... [output truncated after ")
                                        .append(maxChars)
                                        .append(" characters]\n");
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stream closed or process terminated
            }
        }

        public void stop() {
            this.stopped = true;
        }

        public String getCapturedOutput() {
            synchronized (lock) {
                return output.toString();
            }
        }
    }

    private void ensureMavenWrapperPresent(Path workspaceRoot) throws IOException {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();

        // 1. Copy pom.xml if missing
        Path workspacePom = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(workspacePom) && Files.exists(projectRoot.resolve("pom.xml"))) {
            Files.copy(projectRoot.resolve("pom.xml"), workspacePom, StandardCopyOption.REPLACE_EXISTING);
        }

        // 2. Copy mvnw and mvnw.cmd if missing
        Path workspaceMvnw = workspaceRoot.resolve("mvnw");
        if (!Files.exists(workspaceMvnw) && Files.exists(projectRoot.resolve("mvnw"))) {
            Files.copy(projectRoot.resolve("mvnw"), workspaceMvnw, StandardCopyOption.REPLACE_EXISTING);
            File file = workspaceMvnw.toFile();
            file.setExecutable(true, false);
            file.setReadable(true, false);
        }

        Path workspaceMvnwCmd = workspaceRoot.resolve("mvnw.cmd");
        if (!Files.exists(workspaceMvnwCmd) && Files.exists(projectRoot.resolve("mvnw.cmd"))) {
            Files.copy(projectRoot.resolve("mvnw.cmd"), workspaceMvnwCmd, StandardCopyOption.REPLACE_EXISTING);
        }

        // 3. Copy .mvn directory if missing
        Path workspaceDotMvn = workspaceRoot.resolve(".mvn");
        Path projectDotMvn = projectRoot.resolve(".mvn");
        if (!Files.exists(workspaceDotMvn) && Files.isDirectory(projectDotMvn)) {
            copyDirectoryRecursively(projectDotMvn, workspaceDotMvn);
        }
    }

    private void copyDirectoryRecursively(Path source, Path destination) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = destination.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    if (!Files.exists(targetPath)) {
                        Files.createDirectories(targetPath);
                    }
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy wrapper directory: " + e.getMessage(), e);
            }
        });
    }
}
