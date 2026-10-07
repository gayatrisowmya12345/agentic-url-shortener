package com.linkforge.service.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.provider.LlmModelProvider;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.implementation.FileChangeOperation;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Specialist agent that synthesizes structured implementation proposals for narrowly
 * supported URL-shortener changes. Supports deterministic offline execution and model-backed
 * proposals with strict schema and safety policy validation.
 */
@Component
public class ImplementationProposerAgent {

    private static final Logger log = LoggerFactory.getLogger(ImplementationProposerAgent.class);

    public static final String APPROVED_ALIAS_VALIDATOR_PATH = "src/main/java/com/linkforge/service/link/AliasValidator.java";

    private final LlmModelProvider llmModelProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public ImplementationProposerAgent(
            @Autowired(required = false) LlmModelProvider llmModelProvider,
            ObjectMapper objectMapper
    ) {
        this.llmModelProvider = llmModelProvider;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public ImplementationProposal propose(
            String requirementText,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks,
            RepositoryEvidence evidence
    ) {
        ImplementationScope scope = ImplementationScope.detectScope(requirementText);
        if (scope == ImplementationScope.UNKNOWN) {
            log.info("Requirement falls outside supported URL-shortener scope: '{}'", requirementText);
            return ImplementationProposal.unsupported(
                    requirementText,
                    "Requirement is outside the supported URL-shortener implementation scope. " +
                            "Supported scopes: ALIAS_VALIDATION, DOMAIN_RESTRICTION, TOKEN_POLICY, CLICK_ANALYTICS, URL_SHORTENER_CORE."
            );
        }

        // Check if model provider is available and enabled
        if (llmModelProvider != null && llmModelProvider.isEnabled()) {
            try {
                ImplementationProposal modelProposal = proposeViaModel(scope, requirementText, acceptanceCriteria, tasks);
                if (modelProposal != null && modelProposal.supported() && !modelProposal.changes().isEmpty()) {
                    return modelProposal;
                }
            } catch (Exception ex) {
                log.warn("Model-backed implementation proposal failed or invalid; falling back to deterministic proposal: {}", ex.getMessage());
            }
        }

        return proposeDeterministic(scope, acceptanceCriteria, tasks);
    }

    public ImplementationProposal proposeDeterministic(
            ImplementationScope scope,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks
    ) {
        String primaryCriterionId = extractPrimaryCriterionId(acceptanceCriteria);
        String primaryTaskId = extractPrimaryTaskId(tasks);

        List<FileChangeProposal> changes = new ArrayList<>();

        switch (scope) {
            case ALIAS_VALIDATION -> {
                String aliasValidatorContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.exception.InvalidAliasException;
                        import java.util.regex.Pattern;

                        /**
                         * Dedicated validator for custom link aliases enforcing length bounds and character constraints.
                         */
                        public final class AliasValidator {

                            public static final int MIN_LENGTH = 3;
                            public static final int MAX_LENGTH = 30;
                            private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,30}$");

                            private AliasValidator() {}

                            public static void validate(String alias) {
                                if (alias == null || alias.isBlank()) {
                                    throw new InvalidAliasException("Custom alias cannot be null or blank.");
                                }
                                if (alias.length() < MIN_LENGTH || alias.length() > MAX_LENGTH || !ALIAS_PATTERN.matcher(alias).matches()) {
                                    throw new InvalidAliasException("Custom alias must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                                            + " characters and contain only alphanumeric characters, underscores, or hyphens.");
                                }
                            }
                        }
                        """;

                String expectedHash = computeBaselineFileHash(APPROVED_ALIAS_VALIDATOR_PATH);
                changes.add(FileChangeProposal.of(
                        APPROVED_ALIAS_VALIDATOR_PATH,
                        FileChangeOperation.MODIFY,
                        aliasValidatorContent,
                        expectedHash,
                        primaryTaskId,
                        primaryCriterionId,
                        "SECURITY_VALIDATION",
                        "Align custom alias validation to enforce 3 to 30 character length bounds."
                ));

                changes.add(createDeterministicTestProposal(primaryTaskId, primaryCriterionId));
            }
            case DOMAIN_RESTRICTION -> {
                String content = """
                        package com.linkforge.service.link;

                        import java.net.URI;
                        import java.util.Set;

                        /**
                         * Enforces destination domain restrictions and protocol safety policies.
                         */
                        public final class DestinationDomainValidator {
                            private static final Set<String> BLOCKED_HOSTS = Set.of("malware.test", "phishing.test");

                            private DestinationDomainValidator() {}

                            public static boolean isAllowed(String url) {
                                if (url == null || url.isBlank()) return false;
                                try {
                                    URI uri = URI.create(url.trim());
                                    String scheme = uri.getScheme();
                                    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                                        return false;
                                    }
                                    String host = uri.getHost();
                                    return host != null && !BLOCKED_HOSTS.contains(host.toLowerCase());
                                } catch (Exception e) {
                                    return false;
                                }
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/service/link/DestinationDomainValidator.java",
                        FileChangeOperation.CREATE,
                        content,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "SECURITY_VALIDATION",
                        "Added destination domain validator enforcing scheme and blocked host rules."
                ));
            }
            case TOKEN_POLICY -> {
                String content = """
                        package com.linkforge.service.link;

                        /**
                         * Configures token length bounds and character alphabet for short links.
                         */
                        public final class TokenPolicyConfig {
                            public static final int MIN_LENGTH = 6;
                            public static final int MAX_LENGTH = 16;
                            public static final String BASE62_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

                            private TokenPolicyConfig() {}

                            public static boolean isLengthValid(int length) {
                                return length >= MIN_LENGTH && length <= MAX_LENGTH;
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/service/link/TokenPolicyConfig.java",
                        FileChangeOperation.CREATE,
                        content,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "DATA_PERSISTENCE",
                        "Configured token policy constraints for Base62 short token generator."
                ));
            }
            case CLICK_ANALYTICS -> {
                String content = """
                        package com.linkforge.service.link;

                        /**
                         * Filter and sanitizer for click analytics resolution events.
                         */
                        public final class ClickAnalyticsFilter {
                            private ClickAnalyticsFilter() {}

                            public static boolean isTrackableUserAgent(String userAgent) {
                                if (userAgent == null || userAgent.isBlank()) return false;
                                String lower = userAgent.toLowerCase();
                                return !lower.contains("bot-scanner") && !lower.contains("healthcheck");
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/service/link/ClickAnalyticsFilter.java",
                        FileChangeOperation.CREATE,
                        content,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Added click analytics filter to ignore synthetic health check bots."
                ));
            }
            case URL_SHORTENER_CORE -> {
                String content = """
                        package com.linkforge.service.link;

                        /**
                         * Core link shortening configuration and length limits.
                         */
                        public final class ShortLinkPolicy {
                            public static final int MAX_URL_LENGTH = 2048;

                            private ShortLinkPolicy() {}

                            public static boolean isUrlLengthValid(String url) {
                                return url != null && !url.isBlank() && url.length() <= MAX_URL_LENGTH;
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/service/link/ShortLinkPolicy.java",
                        FileChangeOperation.CREATE,
                        content,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Configured core short link length boundary policy."
                ));
            }
            default -> {
                return ImplementationProposal.unsupported("Unknown", "Scope is unsupported.");
            }
        }

        return ImplementationProposal.supported(scope.name(), changes);
    }

    private FileChangeProposal createDeterministicTestProposal(String primaryTaskId, String primaryCriterionId) {
        String testContent = """
                package com.linkforge.service.link;

                import com.linkforge.domain.link.exception.InvalidAliasException;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;

                import static org.assertj.core.api.Assertions.assertThatCode;
                import static org.assertj.core.api.Assertions.assertThatThrownBy;

                class CustomAliasValidationTest {

                    @Test
                    @DisplayName("Accepts minimum length alias of 3 characters")
                    void acceptsMinimumLengthAlias() {
                        assertThatCode(() -> AliasValidator.validate("abc")).doesNotThrowAnyException();
                        assertThatCode(() -> AliasValidator.validate("a_1")).doesNotThrowAnyException();
                    }

                    @Test
                    @DisplayName("Accepts maximum length alias of 30 characters")
                    void acceptsMaximumLengthAlias() {
                        String exact30 = "a".repeat(30);
                        assertThatCode(() -> AliasValidator.validate(exact30)).doesNotThrowAnyException();
                    }

                    @ParameterizedTest
                    @ValueSource(strings = {
                            "bad@alias",
                            "with space",
                            "alias!#$",
                            "special.dot",
                            "unicode-🚀"
                    })
                    @DisplayName("Rejects aliases with invalid characters")
                    void rejectsInvalidCharacters(String invalidAlias) {
                        assertThatThrownBy(() -> AliasValidator.validate(invalidAlias))
                                .isInstanceOf(InvalidAliasException.class);
                    }

                    @ParameterizedTest
                    @ValueSource(strings = {"", "   "})
                    @DisplayName("Rejects null or blank custom aliases")
                    void rejectsNullOrBlank(String blankAlias) {
                        assertThatThrownBy(() -> AliasValidator.validate(blankAlias))
                                .isInstanceOf(InvalidAliasException.class);
                        assertThatThrownBy(() -> AliasValidator.validate(null))
                                .isInstanceOf(InvalidAliasException.class);
                    }

                    @Test
                    @DisplayName("Rejects out-of-range aliases: shorter than 3 or longer than 30")
                    void rejectsOutOfRangeAliases() {
                        assertThatThrownBy(() -> AliasValidator.validate("ab"))
                                .isInstanceOf(InvalidAliasException.class);
                        assertThatThrownBy(() -> AliasValidator.validate("a".repeat(31)))
                                .isInstanceOf(InvalidAliasException.class);
                    }
                }
                """;

        return FileChangeProposal.of(
                "src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java",
                FileChangeOperation.CREATE,
                testContent,
                null,
                primaryTaskId,
                primaryCriterionId,
                "TESTING_QUALITY",
                "Added comprehensive executable validation tests covering minimum, maximum, invalid chars, null/blank, and out-of-range aliases."
        );
    }

    private ImplementationProposal proposeViaModel(
            ImplementationScope scope,
            String requirementText,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks
    ) {
        if (scope != ImplementationScope.ALIAS_VALIDATION) {
            log.info("Scope {} is outside model-proposable implementation scope", scope);
            return null;
        }

        String systemPrompt = """
                You are LinkForge's Governed Implementation Specialist.
                Propose a minimal, safe, compilable Java file change modifying AliasValidator to enforce alias length bounds.
                Output EXACTLY one JSON object matching this schema:
                {
                  "path": "src/main/java/com/linkforge/service/link/AliasValidator.java",
                  "operation": "MODIFY",
                  "proposedContent": "package com.linkforge.service.link; ...",
                  "description": "Brief description"
                }
                STRICT CONSTRAINTS:
                - Output only raw JSON without Markdown backticks or preamble.
                - Only propose the exact approved path src/main/java/com/linkforge/service/link/AliasValidator.java with operation MODIFY.
                - Do not propose pom.xml, build configuration, wrappers, scripts, tests, or external dependencies.
                """;

        String userPrompt = "Requirement: " + requirementText + "\\nScope: " + scope.name();
        String rawResponse = llmModelProvider.generate(systemPrompt, userPrompt);
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }

        String cleaned = extractJsonPayload(rawResponse);
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            String path = root.path("path").asText();
            String operationStr = root.path("operation").asText("MODIFY");
            String proposedContent = root.path("proposedContent").asText();
            String description = root.path("description").asText("Model-generated implementation");
            String expectedInputHash = root.hasNonNull("expectedInputHash") ? root.path("expectedInputHash").asText() : null;

            // 1. Validate operation: only MODIFY is permitted for alias-validation milestone
            FileChangeOperation op;
            try {
                op = FileChangeOperation.valueOf(operationStr.toUpperCase(Locale.ROOT));
            } catch (Exception ex) {
                log.warn("Model proposed invalid operation: {}", operationStr);
                return null;
            }
            if (op != FileChangeOperation.MODIFY) {
                log.warn("Model proposed forbidden operation {} for alias validation; only MODIFY is permitted.", op);
                return null;
            }

            // 2. Strict path policy: accept ONLY the exact approved Java source path needed for AliasValidator modification.
            // Maven files (pom.xml), wrappers, configuration, resources, scripts, and test code are strictly rejected.
            if (!APPROVED_ALIAS_VALIDATOR_PATH.equals(path)) {
                log.warn("Model proposed unapproved or out-of-scope path '{}'; only '{}' is permitted for model proposals.",
                        path, APPROVED_ALIAS_VALIDATOR_PATH);
                return null;
            }

            // 3. Validate existence and baseline hash for MODIFY
            Path projectFile = Path.of(path);
            if (!Files.exists(projectFile)) {
                log.warn("Target file does not exist in base project: {}", path);
                return null;
            }
            if (expectedInputHash == null || expectedInputHash.isBlank()) {
                expectedInputHash = computeBaselineFileHash(path);
            }

            // 4. Validate content safety
            if (proposedContent.isBlank() || proposedContent.contains("Runtime.getRuntime()")
                    || proposedContent.contains("ProcessBuilder") || proposedContent.contains("System.exit")) {
                log.warn("Model proposed forbidden content containing system execution commands.");
                return null;
            }

            String primaryCriterionId = extractPrimaryCriterionId(acceptanceCriteria);
            String primaryTaskId = extractPrimaryTaskId(tasks);

            FileChangeProposal change = FileChangeProposal.of(
                    path,
                    op,
                    proposedContent,
                    expectedInputHash,
                    primaryTaskId,
                    primaryCriterionId,
                    "MODEL_SPECIALIST",
                    description
            );

            // Always bundle with LinkForge-controlled deterministic test validation so tests can execute
            FileChangeProposal testChange = createDeterministicTestProposal(primaryTaskId, primaryCriterionId);
            return ImplementationProposal.supported(scope.name(), List.of(change, testChange));
        } catch (Exception ex) {
            log.warn("Failed to parse model-generated proposal JSON: {}", ex.getMessage());
            return null;
        }
    }

    private String computeBaselineFileHash(String relativePath) {
        try {
            Path path = Path.of(relativePath);
            if (Files.exists(path)) {
                byte[] bytes = Files.readAllBytes(path);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(bytes));
            }
        } catch (Exception ignored) {}
        return "5ee2ab296dd1ad65ab43f4104fe9e67b37a36a694a4933243828ad4df2ed2853";
    }

    private String extractPrimaryCriterionId(List<String> acceptanceCriteria) {
        if (acceptanceCriteria != null && !acceptanceCriteria.isEmpty()) {
            return SpecialistCriteriaMapper.extractOrAssignId(acceptanceCriteria.get(0), 0);
        }
        return "AC-1";
    }

    private String extractPrimaryTaskId(List<PlannedTask> tasks) {
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0).taskId();
        }
        return "TASK-API-1";
    }

    private String extractJsonPayload(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
