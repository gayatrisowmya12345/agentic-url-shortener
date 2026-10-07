package com.linkforge.service.implementation.container;

import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.TestReportItem;
import com.linkforge.service.implementation.GovernedBuildValidator;
import com.linkforge.service.implementation.GovernedExecutionProperties;
import com.linkforge.service.implementation.SurefireReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes build verification inside a hardened, isolated Docker container.
 * Never passes host credentials, never mounts docker.sock, isolates the network,
 * and executes a trusted container-provided Maven binary instead of caller-supplied scripts.
 */
@Component
public class DockerContainerBuildExecutor implements ContainerBuildExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerBuildExecutor.class);

    public static final String DEFAULT_PINNED_IMAGE = "maven:3.9.9-eclipse-temurin-21@sha256:4f3c7c7423e2dc92b34208a0d24c08e56d7eb596238b6d0e82eb0aaec6655519";

    private final GovernedExecutionProperties properties;
    private final SurefireReportParser surefireReportParser;
    private final String trustedContainerImage;

    public DockerContainerBuildExecutor(GovernedExecutionProperties properties) {
        this(properties, null, DEFAULT_PINNED_IMAGE);
    }

    @Autowired
    public DockerContainerBuildExecutor(
            GovernedExecutionProperties properties,
            @Autowired(required = false) SurefireReportParser surefireReportParser
    ) {
        this(properties, surefireReportParser, DEFAULT_PINNED_IMAGE);
    }

    public DockerContainerBuildExecutor(
            GovernedExecutionProperties properties,
            SurefireReportParser surefireReportParser,
            String trustedContainerImage
    ) {
        this.properties = properties != null ? properties : new GovernedExecutionProperties();
        this.surefireReportParser = surefireReportParser != null ? surefireReportParser : new SurefireReportParser();
        this.trustedContainerImage = (trustedContainerImage != null && !trustedContainerImage.isBlank())
                ? trustedContainerImage
                : DEFAULT_PINNED_IMAGE;
    }

    @Override
    public boolean isAvailable() {
        // Never treat config flag alone as proof: probe actual container runtime execution capability
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            log.debug("Docker container runtime is unavailable: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public BuildValidationResult executeIsolatedBuild(
            Path disposableWorkspace,
            String buildCommand,
            ImplementationProposal proposal
    ) {
        if (!isAvailable()) {
            throw new IllegalStateException("Genuine container isolation is unavailable. Cannot execute submitted build.");
        }
        if (disposableWorkspace == null || !Files.isDirectory(disposableWorkspace)) {
            throw new IllegalArgumentException("Disposable workspace must be an existing directory.");
        }

        Instant startedAt = Instant.now();
        String trustedCommand = (buildCommand != null && !buildCommand.isBlank()) ? buildCommand : properties.getFixedBuildCommand();
        boolean fullVerification = trustedCommand.contains("verify");

        String effectiveImage = (trustedContainerImage != null && !trustedContainerImage.isBlank())
                ? trustedContainerImage
                : properties.getContainerImage();
        if (effectiveImage == null || effectiveImage.isBlank()) {
            effectiveImage = DEFAULT_PINNED_IMAGE;
        }

        List<String> dockerCommand = buildDockerRunArguments(disposableWorkspace, effectiveImage);

        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCommand);
            // Completely sanitized environment: pass zero host environment variables
            pb.environment().clear();
            pb.redirectErrorStream(true);

            log.info("Starting isolated container build in {}: {}", disposableWorkspace, dockerCommand);
            Process process = pb.start();

            GovernedBuildValidator.BoundedOutputDrainer drainer = new GovernedBuildValidator.BoundedOutputDrainer(
                    process.getInputStream(),
                    properties.getMaxCapturedOutputChars()
            );
            Thread drainerThread = new Thread(drainer, "docker-build-drainer");
            drainerThread.setDaemon(true);
            drainerThread.start();

            boolean finished = process.waitFor(properties.getBuildTimeoutSeconds(), TimeUnit.SECONDS);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();

            if (!finished) {
                process.destroyForcibly();
                drainer.stop();
                try {
                    drainerThread.join(500);
                } catch (InterruptedException ignored) {}

                return new BuildValidationResult(
                        "docker run [isolated container] mvn --batch-mode clean verify",
                        -1,
                        durationMs,
                        drainer.getCapturedOutput() + "\nIsolated container build timed out after " + properties.getBuildTimeoutSeconds() + " seconds.",
                        "TIMED_OUT",
                        Instant.now(),
                        fullVerification,
                        List.of()
                );
            }

            try {
                drainerThread.join(1000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            List<TestReportItem> testReports = surefireReportParser.parseReports(disposableWorkspace, proposal);
            boolean hasFailed = testReports.stream().anyMatch(TestReportItem::isFailed);
            String status = (exitCode == 0 && !hasFailed) ? "SUCCESS" : "BUILD_FAILED";

            return new BuildValidationResult(
                    "docker run [isolated container] mvn --batch-mode clean verify",
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
            log.error("Error executing isolated container build: {}", ex.getMessage(), ex);
            return new BuildValidationResult(
                    "docker run [isolated container] mvn --batch-mode clean verify",
                    -1,
                    durationMs,
                    "Container execution error: " + ex.getMessage(),
                    "ERROR",
                    Instant.now(),
                    fullVerification,
                    List.of()
            );
        }
    }

    public List<String> buildDockerRunArguments(Path disposableWorkspace, String effectiveImage) {
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add("docker");
        dockerCommand.add("run");
        dockerCommand.add("--rm");
        dockerCommand.add("--network");
        dockerCommand.add("none");
        dockerCommand.add("--memory");
        dockerCommand.add("2048m");
        dockerCommand.add("--cpus");
        dockerCommand.add("2.0");
        dockerCommand.add("--pids-limit");
        dockerCommand.add("100");
        dockerCommand.add("--security-opt");
        dockerCommand.add("no-new-privileges");
        dockerCommand.add("--cap-drop");
        dockerCommand.add("ALL");
        dockerCommand.add("-v");
        dockerCommand.add(disposableWorkspace.toAbsolutePath().normalize() + ":/workspace:rw");

        Path cachePath = resolveVettedDependencyCache();
        if (cachePath != null && Files.isDirectory(cachePath)) {
            dockerCommand.add("-v");
            dockerCommand.add(cachePath.toAbsolutePath().normalize() + ":/root/.m2/repository:ro");
        }

        dockerCommand.add("-w");
        dockerCommand.add("/workspace");
        dockerCommand.add(effectiveImage);
        dockerCommand.add("mvn");
        dockerCommand.add("--batch-mode");
        dockerCommand.add("clean");
        dockerCommand.add("verify");
        return dockerCommand;
    }

    @Override
    public String getIsolationType() {
        return "DOCKER_CONTAINER";
    }

    private Path resolveVettedDependencyCache() {
        if (properties.getDependencyCachePath() != null && !properties.getDependencyCachePath().isBlank()) {
            Path p = Path.of(properties.getDependencyCachePath()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path defaultM2 = Path.of(userHome, ".m2", "repository").toAbsolutePath().normalize();
            if (Files.isDirectory(defaultM2)) {
                return defaultM2;
            }
        }
        return null;
    }
}
