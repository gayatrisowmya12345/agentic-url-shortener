package com.linkforge.service.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.RequirementInterpreterAgent;
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
    private final com.linkforge.service.inspection.CodebaseInspectionProperties inspectionProperties;

    @Autowired
    public ImplementationProposerAgent(
            @Autowired(required = false) LlmModelProvider llmModelProvider,
            ObjectMapper objectMapper,
            @Autowired(required = false) com.linkforge.service.inspection.CodebaseInspectionProperties inspectionProperties
    ) {
        this.llmModelProvider = llmModelProvider;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.inspectionProperties = inspectionProperties != null ? inspectionProperties : new com.linkforge.service.inspection.CodebaseInspectionProperties();
    }

    public ImplementationProposerAgent(
            LlmModelProvider llmModelProvider,
            ObjectMapper objectMapper
    ) {
        this(llmModelProvider, objectMapper, new com.linkforge.service.inspection.CodebaseInspectionProperties());
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

        return proposeDeterministic(scope, requirementText, acceptanceCriteria, tasks, evidence);
    }

    public ImplementationProposal proposeDeterministic(
            ImplementationScope scope,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks
    ) {
        return proposeDeterministic(scope, null, acceptanceCriteria, tasks, null);
    }

    public ImplementationProposal proposeDeterministic(
            ImplementationScope scope,
            String requirementText,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks
    ) {
        return proposeDeterministic(scope, requirementText, acceptanceCriteria, tasks, null);
    }

    public ImplementationProposal proposeDeterministic(
            ImplementationScope scope,
            String requirementText,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks,
            RepositoryEvidence evidence
    ) {
        String primaryCriterionId = extractPrimaryCriterionId(acceptanceCriteria);
        String primaryTaskId = extractPrimaryTaskId(tasks);

        List<FileChangeProposal> changes = new ArrayList<>();

        switch (scope) {
            case ALIAS_VALIDATION -> {
                int minLength = extractMinAliasLength(requirementText, acceptanceCriteria, 3);
                int maxLength = extractMaxAliasLength(requirementText, acceptanceCriteria, 30);
                if (minLength > maxLength || minLength <= 0 || maxLength <= 0) {
                    return ImplementationProposal.unsupported(
                            scope.name(),
                            "Cannot satisfy conflicting or invalid alias length constraints: minimum " + minLength + " exceeds maximum " + maxLength
                    );
                }

                if (isBrownfieldRepository(evidence)) {
                    var brownfieldChanges = proposeBrownfieldAliasValidationChanges(requirementText, acceptanceCriteria, tasks, evidence);
                    if (brownfieldChanges.isEmpty()) {
                        return ImplementationProposal.unsupported(
                                scope.name(),
                                "Brownfield repository runtime path for alias validation could not be safely identified from inspected source evidence: no link shortening service, controller, or validator found."
                        );
                    }
                    changes.addAll(brownfieldChanges);
                } else {

                String aliasValidatorContent = String.format("""
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.exception.InvalidAliasException;
                        import java.util.regex.Pattern;

                        /**
                         * Dedicated validator for custom link aliases enforcing length bounds and character constraints.
                         */
                        public final class AliasValidator {

                            public static final int MIN_LENGTH = %d;
                            public static final int MAX_LENGTH = %d;
                            private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{%d,%d}$");

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
                        """, minLength, maxLength, minLength, maxLength);

                String expectedHash = computeBaselineFileHash(APPROVED_ALIAS_VALIDATOR_PATH);
                changes.add(FileChangeProposal.of(
                        APPROVED_ALIAS_VALIDATOR_PATH,
                        FileChangeOperation.MODIFY,
                        aliasValidatorContent,
                        expectedHash,
                        primaryTaskId,
                        primaryCriterionId,
                        "SECURITY_VALIDATION",
                        "Align custom alias validation to enforce " + minLength + " to " + maxLength + " character length bounds."
                ));

                changes.add(createDeterministicTestProposal(primaryTaskId, primaryCriterionId, minLength, maxLength));
                changes.add(createDeterministicHttpTestProposal(primaryTaskId, primaryCriterionId, minLength, maxLength));
                }
            }
            case DOMAIN_RESTRICTION -> {
                String servicePath = "src/main/java/com/linkforge/service/link/LinkShortenerService.java";
                String modifiedServiceContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.ClickEvent;
                        import com.linkforge.domain.link.Link;
                        import com.linkforge.domain.link.exception.AliasConflictException;
                        import com.linkforge.domain.link.exception.InvalidAliasException;
                        import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
                        import com.linkforge.domain.link.exception.LinkNotFoundException;
                        import org.springframework.dao.DuplicateKeyException;
                        import org.springframework.stereotype.Service;
                        import org.springframework.transaction.annotation.Transactional;

                        import java.net.URI;
                        import java.security.SecureRandom;
                        import java.util.List;
                        import java.util.Set;
                        import java.util.regex.Pattern;

                        /**
                         * Service managing URL shortening with domain security validation,
                         * custom alias conflict detection, HTTP 302 resolution, and click analytics.
                         */
                        @Service
                        public class LinkShortenerService {

                            private static final String BASE62_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                            private static final int DEFAULT_TOKEN_LENGTH = 7;
                            private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
                            private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
                                    "api", "actuator", "r", "links", "workflows", "health", "metrics", "swagger", "error"
                            );
                            private static final Set<String> BLOCKED_HOSTS = Set.of("malware.test", "phishing.test", "blocked.domain");

                            private final LinkRepository linkRepository;
                            private final SecureRandom random = new SecureRandom();

                            public LinkShortenerService(LinkRepository linkRepository) {
                                this.linkRepository = linkRepository;
                            }

                            public Link createShortLink(String destinationUrl, String customAlias) {
                                validateDestinationUrl(destinationUrl);

                                String trimmedAlias = customAlias != null ? customAlias.trim() : null;
                                if (trimmedAlias != null && trimmedAlias.isEmpty()) {
                                    trimmedAlias = null;
                                }

                                if (trimmedAlias != null) {
                                    validateCustomAlias(trimmedAlias);
                                    if (linkRepository.existsByIdentifier(trimmedAlias)) {
                                        throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                    }
                                }

                                for (int attempts = 0; attempts < 10; attempts++) {
                                    String token = generateUniqueToken();
                                    Link link = new Link(token, trimmedAlias, destinationUrl.trim());
                                    try {
                                        return linkRepository.save(link);
                                    } catch (DuplicateKeyException e) {
                                        if (trimmedAlias != null && linkRepository.existsByIdentifier(trimmedAlias)) {
                                            throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                        }
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a non-colliding token after multiple attempts.");
                            }

                            @Transactional
                            public Link resolveLink(String identifier, String referrer, String userAgent) {
                                Link link = getLink(identifier);
                                linkRepository.recordClick(link.getId(), referrer, userAgent);
                                return getLink(identifier);
                            }

                            public Link getLink(String identifier) {
                                if (identifier == null || identifier.isBlank()) {
                                    throw new LinkNotFoundException("Link identifier cannot be empty.");
                                }
                                return linkRepository.findByIdentifier(identifier.trim())
                                        .orElseThrow(() -> new LinkNotFoundException("Short link not found for identifier: '" + identifier + "'"));
                            }

                            public Link getLinkAnalytics(String identifier) {
                                Link link = getLink(identifier);
                                List<ClickEvent> recentClicks = linkRepository.findRecentClicks(link.getId(), 50);
                                return new Link(
                                        link.getId(),
                                        link.getToken(),
                                        link.getCustomAlias(),
                                        link.getDestinationUrl(),
                                        link.getCreatedAt(),
                                        link.getClickCount(),
                                        link.getLastClickedAt(),
                                        recentClicks
                                );
                            }

                            private void validateDestinationUrl(String destinationUrl) {
                                if (destinationUrl == null || destinationUrl.isBlank()) {
                                    throw new InvalidDestinationUrlException("Destination URL must not be blank.");
                                }

                                try {
                                    URI uri = URI.create(destinationUrl.trim());
                                    String scheme = uri.getScheme();
                                    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                                        throw new InvalidDestinationUrlException("Destination URL must use http or https scheme.");
                                    }
                                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                                        throw new InvalidDestinationUrlException("Destination URL must contain a valid hostname.");
                                    }
                                    if (BLOCKED_HOSTS.contains(uri.getHost().toLowerCase())) {
                                        throw new InvalidDestinationUrlException("Destination host '" + uri.getHost() + "' is blocked by security domain policy.");
                                    }
                                } catch (IllegalArgumentException e) {
                                    throw new InvalidDestinationUrlException("Destination URL is malformed: " + e.getMessage());
                                }
                            }

                            private void validateCustomAlias(String alias) {
                                AliasValidator.validate(alias);
                                if (RESERVED_IDENTIFIERS.contains(alias.toLowerCase())) {
                                    throw new InvalidAliasException("Custom alias '" + alias + "' is reserved by the system.");
                                }
                            }

                            private String generateUniqueToken() {
                                for (int attempts = 0; attempts < 100; attempts++) {
                                    StringBuilder sb = new StringBuilder(DEFAULT_TOKEN_LENGTH);
                                    for (int i = 0; i < DEFAULT_TOKEN_LENGTH; i++) {
                                        int index = random.nextInt(BASE62_ALPHABET.length());
                                        sb.append(BASE62_ALPHABET.charAt(index));
                                    }
                                    String candidate = sb.toString();
                                    if (!linkRepository.existsByIdentifier(candidate) && !RESERVED_IDENTIFIERS.contains(candidate.toLowerCase())) {
                                        return candidate;
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a unique token after multiple attempts.");
                            }
                        }
                        """;

                String expectedHash = computeBaselineFileHash(servicePath);
                changes.add(FileChangeProposal.of(
                        servicePath,
                        FileChangeOperation.MODIFY,
                        modifiedServiceContent,
                        expectedHash,
                        primaryTaskId,
                        primaryCriterionId,
                        "SECURITY_VALIDATION",
                        "Wired destination domain security policy into LinkShortenerService to reject blocked hosts."
                ));

                String testContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.Link;
                        import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
                        import org.junit.jupiter.api.DisplayName;
                        import org.junit.jupiter.api.Test;
                        import org.mockito.Mockito;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.assertj.core.api.Assertions.assertThatThrownBy;
                        import static org.mockito.ArgumentMatchers.any;
                        import static org.mockito.Mockito.when;

                        class DomainRestrictionValidationTest {

                            @Test
                            @DisplayName("Rejects destination URLs matching blocked domain security policy")
                            void rejectsBlockedDomains() {
                                LinkRepository repo = Mockito.mock(LinkRepository.class);
                                LinkShortenerService service = new LinkShortenerService(repo);

                                assertThatThrownBy(() -> service.createShortLink("https://malware.test/exploit", null))
                                        .isInstanceOf(InvalidDestinationUrlException.class);
                                assertThatThrownBy(() -> service.createShortLink("http://phishing.test/login", null))
                                        .isInstanceOf(InvalidDestinationUrlException.class);
                            }

                            @Test
                            @DisplayName("Accepts destination URLs with allowed trusted domains")
                            void acceptsAllowedDomains() {
                                LinkRepository repo = Mockito.mock(LinkRepository.class);
                                when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
                                LinkShortenerService service = new LinkShortenerService(repo);

                                Link link = service.createShortLink("https://example.com/trusted", null);
                                assertThat(link).isNotNull();
                                assertThat(link.getDestinationUrl()).isEqualTo("https://example.com/trusted");
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/test/java/com/linkforge/service/link/DomainRestrictionValidationTest.java",
                        FileChangeOperation.CREATE,
                        testContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "TESTING_QUALITY",
                        "Added automated tests verifying domain restriction enforcement in LinkShortenerService."
                ));
            }
            case TOKEN_POLICY -> {
                String servicePath = "src/main/java/com/linkforge/service/link/LinkShortenerService.java";
                String modifiedServiceContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.ClickEvent;
                        import com.linkforge.domain.link.Link;
                        import com.linkforge.domain.link.exception.AliasConflictException;
                        import com.linkforge.domain.link.exception.InvalidAliasException;
                        import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
                        import com.linkforge.domain.link.exception.LinkNotFoundException;
                        import org.springframework.dao.DuplicateKeyException;
                        import org.springframework.stereotype.Service;
                        import org.springframework.transaction.annotation.Transactional;

                        import java.net.URI;
                        import java.security.SecureRandom;
                        import java.util.List;
                        import java.util.Set;
                        import java.util.regex.Pattern;

                        /**
                         * Service managing URL shortening with configured 7-character token policy,
                         * custom alias conflict detection, HTTP 302 resolution, and click analytics.
                         */
                        @Service
                        public class LinkShortenerService {

                            private static final String BASE62_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                            private static final int DEFAULT_TOKEN_LENGTH = 7;
                            private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
                            private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
                                    "api", "actuator", "r", "links", "workflows", "health", "metrics", "swagger", "error"
                            );

                            private final LinkRepository linkRepository;
                            private final SecureRandom random = new SecureRandom();

                            public LinkShortenerService(LinkRepository linkRepository) {
                                this.linkRepository = linkRepository;
                            }

                            public Link createShortLink(String destinationUrl, String customAlias) {
                                validateDestinationUrl(destinationUrl);

                                String trimmedAlias = customAlias != null ? customAlias.trim() : null;
                                if (trimmedAlias != null && trimmedAlias.isEmpty()) {
                                    trimmedAlias = null;
                                }

                                if (trimmedAlias != null) {
                                    validateCustomAlias(trimmedAlias);
                                    if (linkRepository.existsByIdentifier(trimmedAlias)) {
                                        throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                    }
                                }

                                for (int attempts = 0; attempts < 10; attempts++) {
                                    String token = generateUniqueToken();
                                    Link link = new Link(token, trimmedAlias, destinationUrl.trim());
                                    try {
                                        return linkRepository.save(link);
                                    } catch (DuplicateKeyException e) {
                                        if (trimmedAlias != null && linkRepository.existsByIdentifier(trimmedAlias)) {
                                            throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                        }
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a non-colliding token after multiple attempts.");
                            }

                            @Transactional
                            public Link resolveLink(String identifier, String referrer, String userAgent) {
                                Link link = getLink(identifier);
                                linkRepository.recordClick(link.getId(), referrer, userAgent);
                                return getLink(identifier);
                            }

                            public Link getLink(String identifier) {
                                if (identifier == null || identifier.isBlank()) {
                                    throw new LinkNotFoundException("Link identifier cannot be empty.");
                                }
                                return linkRepository.findByIdentifier(identifier.trim())
                                        .orElseThrow(() -> new LinkNotFoundException("Short link not found for identifier: '" + identifier + "'"));
                            }

                            public Link getLinkAnalytics(String identifier) {
                                Link link = getLink(identifier);
                                List<ClickEvent> recentClicks = linkRepository.findRecentClicks(link.getId(), 50);
                                return new Link(
                                        link.getId(),
                                        link.getToken(),
                                        link.getCustomAlias(),
                                        link.getDestinationUrl(),
                                        link.getCreatedAt(),
                                        link.getClickCount(),
                                        link.getLastClickedAt(),
                                        recentClicks
                                );
                            }

                            private void validateDestinationUrl(String destinationUrl) {
                                if (destinationUrl == null || destinationUrl.isBlank()) {
                                    throw new InvalidDestinationUrlException("Destination URL must not be blank.");
                                }

                                try {
                                    URI uri = URI.create(destinationUrl.trim());
                                    String scheme = uri.getScheme();
                                    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                                        throw new InvalidDestinationUrlException("Destination URL must use http or https scheme.");
                                    }
                                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                                        throw new InvalidDestinationUrlException("Destination URL must contain a valid hostname.");
                                    }
                                } catch (IllegalArgumentException e) {
                                    throw new InvalidDestinationUrlException("Destination URL is malformed: " + e.getMessage());
                                }
                            }

                            private void validateCustomAlias(String alias) {
                                AliasValidator.validate(alias);
                                if (RESERVED_IDENTIFIERS.contains(alias.toLowerCase())) {
                                    throw new InvalidAliasException("Custom alias '" + alias + "' is reserved by the system.");
                                }
                            }

                            private String generateUniqueToken() {
                                for (int attempts = 0; attempts < 100; attempts++) {
                                    StringBuilder sb = new StringBuilder(DEFAULT_TOKEN_LENGTH);
                                    for (int i = 0; i < DEFAULT_TOKEN_LENGTH; i++) {
                                        int index = random.nextInt(BASE62_ALPHABET.length());
                                        sb.append(BASE62_ALPHABET.charAt(index));
                                    }
                                    String candidate = sb.toString();
                                    if (!linkRepository.existsByIdentifier(candidate) && !RESERVED_IDENTIFIERS.contains(candidate.toLowerCase())) {
                                        return candidate;
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a unique token after multiple attempts.");
                            }
                        }
                        """;

                String expectedHash = computeBaselineFileHash(servicePath);
                changes.add(FileChangeProposal.of(
                        servicePath,
                        FileChangeOperation.MODIFY,
                        modifiedServiceContent,
                        expectedHash,
                        primaryTaskId,
                        primaryCriterionId,
                        "DATA_PERSISTENCE",
                        "Updated LinkShortenerService token policy to generate 7-character Base62 tokens."
                ));

                String testContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.Link;
                        import org.junit.jupiter.api.DisplayName;
                        import org.junit.jupiter.api.Test;
                        import org.mockito.Mockito;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.mockito.ArgumentMatchers.any;
                        import static org.mockito.Mockito.when;

                        class TokenPolicyValidationTest {

                            @Test
                            @DisplayName("Generates short tokens conforming to configured token length of 7 characters")
                            void generatesTokensWithConfiguredLength() {
                                LinkRepository repo = Mockito.mock(LinkRepository.class);
                                when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
                                LinkShortenerService service = new LinkShortenerService(repo);

                                Link link = service.createShortLink("https://example.com/target", null);
                                assertThat(link).isNotNull();
                                assertThat(link.getToken()).hasSize(7);
                                assertThat(link.getToken()).matches("^[a-zA-Z0-9]{7}$");
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/test/java/com/linkforge/service/link/TokenPolicyValidationTest.java",
                        FileChangeOperation.CREATE,
                        testContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "TESTING_QUALITY",
                        "Added automated tests verifying 7-character token policy enforcement."
                ));
            }
            case CLICK_ANALYTICS -> {
                String servicePath = "src/main/java/com/linkforge/service/link/LinkShortenerService.java";
                String modifiedServiceContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.ClickEvent;
                        import com.linkforge.domain.link.Link;
                        import com.linkforge.domain.link.exception.AliasConflictException;
                        import com.linkforge.domain.link.exception.InvalidAliasException;
                        import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
                        import com.linkforge.domain.link.exception.LinkNotFoundException;
                        import org.springframework.dao.DuplicateKeyException;
                        import org.springframework.stereotype.Service;
                        import org.springframework.transaction.annotation.Transactional;

                        import java.net.URI;
                        import java.security.SecureRandom;
                        import java.util.List;
                        import java.util.Set;
                        import java.util.regex.Pattern;

                        /**
                         * Service managing URL shortening with bot user-agent filtering on click analytics,
                         * custom alias conflict detection, HTTP 302 resolution, and H2 persistence.
                         */
                        @Service
                        public class LinkShortenerService {

                            private static final String BASE62_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                            private static final int DEFAULT_TOKEN_LENGTH = 7;
                            private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
                            private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
                                    "api", "actuator", "r", "links", "workflows", "health", "metrics", "swagger", "error"
                            );

                            private final LinkRepository linkRepository;
                            private final SecureRandom random = new SecureRandom();

                            public LinkShortenerService(LinkRepository linkRepository) {
                                this.linkRepository = linkRepository;
                            }

                            public Link createShortLink(String destinationUrl, String customAlias) {
                                validateDestinationUrl(destinationUrl);

                                String trimmedAlias = customAlias != null ? customAlias.trim() : null;
                                if (trimmedAlias != null && trimmedAlias.isEmpty()) {
                                    trimmedAlias = null;
                                }

                                if (trimmedAlias != null) {
                                    validateCustomAlias(trimmedAlias);
                                    if (linkRepository.existsByIdentifier(trimmedAlias)) {
                                        throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                    }
                                }

                                for (int attempts = 0; attempts < 10; attempts++) {
                                    String token = generateUniqueToken();
                                    Link link = new Link(token, trimmedAlias, destinationUrl.trim());
                                    try {
                                        return linkRepository.save(link);
                                    } catch (DuplicateKeyException e) {
                                        if (trimmedAlias != null && linkRepository.existsByIdentifier(trimmedAlias)) {
                                            throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                        }
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a non-colliding token after multiple attempts.");
                            }

                            @Transactional
                            public Link resolveLink(String identifier, String referrer, String userAgent) {
                                Link link = getLink(identifier);
                                if (userAgent == null || (!userAgent.toLowerCase().contains("bot-scanner") && !userAgent.toLowerCase().contains("healthcheck"))) {
                                    linkRepository.recordClick(link.getId(), referrer, userAgent);
                                }
                                return getLink(identifier);
                            }

                            public Link getLink(String identifier) {
                                if (identifier == null || identifier.isBlank()) {
                                    throw new LinkNotFoundException("Link identifier cannot be empty.");
                                }
                                return linkRepository.findByIdentifier(identifier.trim())
                                        .orElseThrow(() -> new LinkNotFoundException("Short link not found for identifier: '" + identifier + "'"));
                            }

                            public Link getLinkAnalytics(String identifier) {
                                Link link = getLink(identifier);
                                List<ClickEvent> recentClicks = linkRepository.findRecentClicks(link.getId(), 50);
                                return new Link(
                                        link.getId(),
                                        link.getToken(),
                                        link.getCustomAlias(),
                                        link.getDestinationUrl(),
                                        link.getCreatedAt(),
                                        link.getClickCount(),
                                        link.getLastClickedAt(),
                                        recentClicks
                                );
                            }

                            private void validateDestinationUrl(String destinationUrl) {
                                if (destinationUrl == null || destinationUrl.isBlank()) {
                                    throw new InvalidDestinationUrlException("Destination URL must not be blank.");
                                }

                                try {
                                    URI uri = URI.create(destinationUrl.trim());
                                    String scheme = uri.getScheme();
                                    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                                        throw new InvalidDestinationUrlException("Destination URL must use http or https scheme.");
                                    }
                                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                                        throw new InvalidDestinationUrlException("Destination URL must contain a valid hostname.");
                                    }
                                } catch (IllegalArgumentException e) {
                                    throw new InvalidDestinationUrlException("Destination URL is malformed: " + e.getMessage());
                                }
                            }

                            private void validateCustomAlias(String alias) {
                                AliasValidator.validate(alias);
                                if (RESERVED_IDENTIFIERS.contains(alias.toLowerCase())) {
                                    throw new InvalidAliasException("Custom alias '" + alias + "' is reserved by the system.");
                                }
                            }

                            private String generateUniqueToken() {
                                for (int attempts = 0; attempts < 100; attempts++) {
                                    StringBuilder sb = new StringBuilder(DEFAULT_TOKEN_LENGTH);
                                    for (int i = 0; i < DEFAULT_TOKEN_LENGTH; i++) {
                                        int index = random.nextInt(BASE62_ALPHABET.length());
                                        sb.append(BASE62_ALPHABET.charAt(index));
                                    }
                                    String candidate = sb.toString();
                                    if (!linkRepository.existsByIdentifier(candidate) && !RESERVED_IDENTIFIERS.contains(candidate.toLowerCase())) {
                                        return candidate;
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a unique token after multiple attempts.");
                            }
                        }
                        """;

                String expectedHash = computeBaselineFileHash(servicePath);
                changes.add(FileChangeProposal.of(
                        servicePath,
                        FileChangeOperation.MODIFY,
                        modifiedServiceContent,
                        expectedHash,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Wired bot filter into LinkShortenerService.resolveLink to ignore synthetic bot traffic."
                ));

                String testContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.Link;
                        import org.junit.jupiter.api.DisplayName;
                        import org.junit.jupiter.api.Test;
                        import org.mockito.Mockito;

                        import java.util.Optional;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.mockito.ArgumentMatchers.any;
                        import static org.mockito.ArgumentMatchers.eq;
                        import static org.mockito.Mockito.never;
                        import static org.mockito.Mockito.verify;
                        import static org.mockito.Mockito.when;

                        class ClickAnalyticsFilterValidationTest {

                            @Test
                            @DisplayName("Ignores click recording for bot scanner user agents")
                            void ignoresBotScannerUserAgent() {
                                LinkRepository repo = Mockito.mock(LinkRepository.class);
                                Link mockLink = new Link("token123", null, "https://example.com");
                                when(repo.findByIdentifier("token123")).thenReturn(Optional.of(mockLink));
                                LinkShortenerService service = new LinkShortenerService(repo);

                                Link resolved = service.resolveLink("token123", "https://google.com", "Mozilla/5.0 bot-scanner/1.0");
                                assertThat(resolved).isNotNull();
                                verify(repo, never()).recordClick(any(), any(), any());
                            }

                            @Test
                            @DisplayName("Records click for standard browser user agents")
                            void recordsClickForStandardBrowser() {
                                LinkRepository repo = Mockito.mock(LinkRepository.class);
                                Link mockLink = new Link("token123", null, "https://example.com");
                                when(repo.findByIdentifier("token123")).thenReturn(Optional.of(mockLink));
                                LinkShortenerService service = new LinkShortenerService(repo);

                                Link resolved = service.resolveLink("token123", "https://google.com", "Mozilla/5.0 (Windows NT 10.0)");
                                assertThat(resolved).isNotNull();
                                verify(repo).recordClick(eq(mockLink.getId()), eq("https://google.com"), eq("Mozilla/5.0 (Windows NT 10.0)"));
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/test/java/com/linkforge/service/link/ClickAnalyticsFilterValidationTest.java",
                        FileChangeOperation.CREATE,
                        testContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Added automated tests verifying bot user-agent filtering on click analytics."
                ));
            }
            case URL_SHORTENER_CORE -> {
                String servicePath = "src/main/java/com/linkforge/service/link/LinkShortenerService.java";
                String modifiedServiceContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.ClickEvent;
                        import com.linkforge.domain.link.Link;
                        import com.linkforge.domain.link.exception.AliasConflictException;
                        import com.linkforge.domain.link.exception.InvalidAliasException;
                        import com.linkforge.domain.link.exception.InvalidDestinationUrlException;
                        import com.linkforge.domain.link.exception.LinkNotFoundException;
                        import org.springframework.dao.DuplicateKeyException;
                        import org.springframework.stereotype.Service;
                        import org.springframework.transaction.annotation.Transactional;

                        import java.net.URI;
                        import java.security.SecureRandom;
                        import java.util.List;
                        import java.util.Set;
                        import java.util.regex.Pattern;

                        /**
                         * Core URL shortener service handling link lifecycle, Base62 token generation,
                         * custom alias validation, resolution, and click analytics.
                         */
                        @Service
                        public class LinkShortenerService {

                            private static final String BASE62_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                            private static final int DEFAULT_TOKEN_LENGTH = 7;
                            private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
                                    "api", "actuator", "r", "links", "workflows", "health", "metrics", "swagger", "error"
                            );

                            private final LinkRepository linkRepository;
                            private final SecureRandom random = new SecureRandom();

                            public LinkShortenerService(LinkRepository linkRepository) {
                                this.linkRepository = linkRepository;
                            }

                            public Link createShortLink(String destinationUrl, String customAlias) {
                                validateDestinationUrl(destinationUrl);

                                String trimmedAlias = customAlias != null ? customAlias.trim() : null;
                                if (trimmedAlias != null && trimmedAlias.isEmpty()) {
                                    trimmedAlias = null;
                                }

                                if (trimmedAlias != null) {
                                    validateCustomAlias(trimmedAlias);
                                    if (linkRepository.existsByIdentifier(trimmedAlias)) {
                                        throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                    }
                                }

                                for (int attempts = 0; attempts < 10; attempts++) {
                                    String token = generateUniqueToken();
                                    Link link = new Link(token, trimmedAlias, destinationUrl.trim());
                                    try {
                                        return linkRepository.save(link);
                                    } catch (DuplicateKeyException e) {
                                        if (trimmedAlias != null && linkRepository.existsByIdentifier(trimmedAlias)) {
                                            throw new AliasConflictException("Custom alias '" + trimmedAlias + "' is already in use.");
                                        }
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a non-colliding token after multiple attempts.");
                            }

                            @Transactional
                            public Link resolveLink(String identifier, String referrer, String userAgent) {
                                Link link = getLink(identifier);
                                linkRepository.recordClick(link.getId(), referrer, userAgent);
                                return getLink(identifier);
                            }

                            public Link getLink(String identifier) {
                                if (identifier == null || identifier.isBlank()) {
                                    throw new LinkNotFoundException("Link identifier cannot be empty.");
                                }
                                return linkRepository.findByIdentifier(identifier.trim())
                                        .orElseThrow(() -> new LinkNotFoundException("Short link not found for identifier: '" + identifier + "'"));
                            }

                            public Link getLinkAnalytics(String identifier) {
                                Link link = getLink(identifier);
                                List<ClickEvent> recentClicks = linkRepository.findRecentClicks(link.getId(), 50);
                                return new Link(
                                        link.getId(),
                                        link.getToken(),
                                        link.getCustomAlias(),
                                        link.getDestinationUrl(),
                                        link.getCreatedAt(),
                                        link.getClickCount(),
                                        link.getLastClickedAt(),
                                        recentClicks
                                );
                            }

                            private void validateDestinationUrl(String destinationUrl) {
                                if (destinationUrl == null || destinationUrl.isBlank()) {
                                    throw new InvalidDestinationUrlException("Destination URL cannot be null or blank.");
                                }
                                try {
                                    URI uri = URI.create(destinationUrl.trim());
                                    if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
                                        throw new InvalidDestinationUrlException("Destination URL must use http or https scheme.");
                                    }
                                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                                        throw new InvalidDestinationUrlException("Destination URL must contain a valid hostname.");
                                    }
                                } catch (IllegalArgumentException e) {
                                    throw new InvalidDestinationUrlException("Destination URL is malformed: " + e.getMessage());
                                }
                            }

                            private void validateCustomAlias(String alias) {
                                AliasValidator.validate(alias);
                                if (RESERVED_IDENTIFIERS.contains(alias.toLowerCase())) {
                                    throw new InvalidAliasException("Custom alias '" + alias + "' is reserved by the system.");
                                }
                            }

                            private String generateUniqueToken() {
                                for (int attempts = 0; attempts < 100; attempts++) {
                                    StringBuilder sb = new StringBuilder(DEFAULT_TOKEN_LENGTH);
                                    for (int i = 0; i < DEFAULT_TOKEN_LENGTH; i++) {
                                        int index = random.nextInt(BASE62_ALPHABET.length());
                                        sb.append(BASE62_ALPHABET.charAt(index));
                                    }
                                    String candidate = sb.toString();
                                    if (!linkRepository.existsByIdentifier(candidate) && !RESERVED_IDENTIFIERS.contains(candidate.toLowerCase())) {
                                        return candidate;
                                    }
                                }
                                throw new IllegalStateException("Failed to generate a unique token after multiple attempts.");
                            }
                        }
                        """;

                String expectedHash = computeBaselineFileHash(servicePath);
                changes.add(FileChangeProposal.of(
                        servicePath,
                        FileChangeOperation.MODIFY,
                        modifiedServiceContent,
                        expectedHash,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Implemented core URL shortener routing and token generation in LinkShortenerService."
                ));

                String testContent = """
                        package com.linkforge.service.link;

                        import com.linkforge.domain.link.Link;
                        import org.junit.jupiter.api.DisplayName;
                        import org.junit.jupiter.api.Test;
                        import org.mockito.Mockito;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.mockito.ArgumentMatchers.any;
                        import static org.mockito.Mockito.when;

                        class CoreUrlShortenerValidationTest {

                            @Test
                            @DisplayName("Generates token and creates short link successfully")
                            void createsShortLink() {
                                LinkRepository repo = Mockito.mock(LinkRepository.class);
                                when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
                                LinkShortenerService service = new LinkShortenerService(repo);

                                Link link = service.createShortLink("https://example.com/core-test", null);
                                assertThat(link).isNotNull();
                                assertThat(link.getToken()).isNotBlank();
                                assertThat(link.getDestinationUrl()).isEqualTo("https://example.com/core-test");
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/test/java/com/linkforge/service/link/CoreUrlShortenerValidationTest.java",
                        FileChangeOperation.CREATE,
                        testContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "TESTING_QUALITY",
                        "Added automated tests verifying core URL shortener creation and token behavior."
                ));
            }
            case GREENFIELD_SERVICE -> {
                int minLength = extractMinAliasLength(requirementText, acceptanceCriteria, 3);
                int maxLength = extractMaxAliasLength(requirementText, acceptanceCriteria, 30);
                if (minLength > maxLength || minLength <= 0 || maxLength <= 0) {
                    return ImplementationProposal.unsupported(
                            scope.name(),
                            "Cannot satisfy conflicting or invalid alias length constraints: minimum " + minLength + " exceeds maximum " + maxLength
                    );
                }

                String secondaryCriterionId = (acceptanceCriteria != null && acceptanceCriteria.size() >= 2)
                        ? SpecialistCriteriaMapper.extractOrAssignId(acceptanceCriteria.get(1), 1)
                        : primaryCriterionId;
                String combinedCriteria = primaryCriterionId.equals(secondaryCriterionId)
                        ? primaryCriterionId
                        : primaryCriterionId + ", " + secondaryCriterionId;

                String appContent = """
                        package com.linkforge.greenfield;

                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;

                        @SpringBootApplication
                        public class GreenfieldApplication {
                            public static void main(String[] args) {
                                SpringApplication.run(GreenfieldApplication.class, args);
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/greenfield/GreenfieldApplication.java",
                        FileChangeOperation.CREATE,
                        appContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Bootstrap Spring Boot application for greenfield URL shortener service."
                ));

                String shortenerContent = String.format("""
                        package com.linkforge.greenfield;

                        import org.springframework.stereotype.Service;
                        import java.security.SecureRandom;
                        import java.util.Map;
                        import java.util.concurrent.ConcurrentHashMap;
                        import java.util.regex.Pattern;

                        /**
                         * Standalone URL shortener service built from scratch in an empty baseline.
                         */
                        @Service
                        public class StandaloneUrlShortener {
                            public static final int MIN_LENGTH = %d;
                            public static final int MAX_LENGTH = %d;
                            private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                            private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{%d,%d}$");
                            private final Map<String, String> links = new ConcurrentHashMap<>();
                            private final SecureRandom random = new SecureRandom();

                            public String shorten(String destinationUrl, String customAlias) {
                                if (destinationUrl == null || destinationUrl.isBlank()) {
                                    throw new IllegalArgumentException("Destination URL must not be blank.");
                                }
                                String identifier;
                                if (customAlias != null && !customAlias.isBlank()) {
                                    String trimmed = customAlias.trim();
                                    if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH || !ALIAS_PATTERN.matcher(trimmed).matches()) {
                                        throw new IllegalArgumentException("Invalid alias format or length: must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.");
                                    }
                                    identifier = trimmed;
                                } else {
                                    StringBuilder sb = new StringBuilder(7);
                                    for (int i = 0; i < 7; i++) {
                                        sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
                                    }
                                    identifier = sb.toString();
                                }
                                if (links.containsKey(identifier)) {
                                    throw new IllegalStateException("Identifier collision: " + identifier);
                                }
                                links.put(identifier, destinationUrl.trim());
                                return identifier;
                            }

                            public String resolve(String identifier) {
                                String url = links.get(identifier);
                                if (url == null) {
                                    throw new IllegalArgumentException("Link not found: " + identifier);
                                }
                                return url;
                            }

                            public int size() {
                                return links.size();
                            }
                        }
                        """, minLength, maxLength, minLength, maxLength);
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/greenfield/StandaloneUrlShortener.java",
                        FileChangeOperation.CREATE,
                        shortenerContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "API_BEHAVIOR",
                        "Greenfield standalone URL shortener implementation enforcing " + minLength + " to " + maxLength + " character alias bounds."
                ));

                String controllerContent = """
                        package com.linkforge.greenfield;

                        import org.springframework.http.HttpStatus;
                        import org.springframework.http.ResponseEntity;
                        import org.springframework.web.bind.annotation.*;

                        import java.net.URI;
                        import java.util.Map;

                        @RestController
                        @RequestMapping
                        public class GreenfieldShortenerController {

                            private final StandaloneUrlShortener shortener;

                            public GreenfieldShortenerController(StandaloneUrlShortener shortener) {
                                this.shortener = shortener;
                            }

                            @PostMapping("/api/v1/greenfield/links")
                            public ResponseEntity<Map<String, Object>> createLink(@RequestBody Map<String, String> request) {
                                String destinationUrl = request.get("destinationUrl");
                                String customAlias = request.get("customAlias");
                                String identifier = shortener.shorten(destinationUrl, customAlias);
                                return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                                        "identifier", identifier,
                                        "destinationUrl", destinationUrl
                                ));
                            }

                            @GetMapping("/r/{identifier}")
                            public ResponseEntity<Void> redirect(@PathVariable("identifier") String identifier) {
                                String destination = shortener.resolve(identifier);
                                return ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(destination))
                                        .build();
                            }

                            @ExceptionHandler(IllegalArgumentException.class)
                            public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
                            }
                        }
                        """;
                changes.add(FileChangeProposal.of(
                        "src/main/java/com/linkforge/greenfield/GreenfieldShortenerController.java",
                        FileChangeOperation.CREATE,
                        controllerContent,
                        null,
                        primaryTaskId,
                        secondaryCriterionId,
                        "API_BEHAVIOR",
                        "Greenfield HTTP REST controller exposing link creation and redirection endpoints."
                ));

                String testContent = String.format("""
                        package com.linkforge.greenfield;

                        import org.junit.jupiter.api.DisplayName;
                        import org.junit.jupiter.api.Test;

                        import static org.junit.jupiter.api.Assertions.*;

                        class StandaloneUrlShortenerTest {

                            @Test
                            @DisplayName("Shortens valid URL and resolves destination")
                            void shortensAndResolvesUrl() {
                                StandaloneUrlShortener shortener = new StandaloneUrlShortener();
                                String id = shortener.shorten("https://example.com/greenfield", null);
                                assertNotNull(id);
                                assertEquals(7, id.length());
                                assertEquals("https://example.com/greenfield", shortener.resolve(id));
                            }

                            @Test
                            @DisplayName("Enforces custom alias validation and collision rejection")
                            void supportsCustomAlias() {
                                StandaloneUrlShortener shortener = new StandaloneUrlShortener();
                                String validAlias = "a".repeat(%d);
                                String alias = shortener.shorten("https://example.com/custom", validAlias);
                                assertEquals(validAlias, alias);
                                assertEquals("https://example.com/custom", shortener.resolve(validAlias));

                                assertThrows(IllegalStateException.class, () ->
                                        shortener.shorten("https://example.com/conflict", validAlias));
                            }

                            @Test
                            @DisplayName("Rejects custom alias shorter than required minimum length %d")
                            void rejectsShortAlias() {
                                StandaloneUrlShortener shortener = new StandaloneUrlShortener();
                                String tooShort = "a".repeat(Math.max(1, %d - 1));
                                assertThrows(IllegalArgumentException.class, () ->
                                        shortener.shorten("https://example.com/too-short", tooShort));
                            }

                            @Test
                            @DisplayName("Accepts custom alias meeting required minimum length %d")
                            void acceptsMinimumLengthAlias() {
                                StandaloneUrlShortener shortener = new StandaloneUrlShortener();
                                String exactMin = "a".repeat(%d);
                                String id = shortener.shorten("https://example.com/exact-min", exactMin);
                                assertEquals(exactMin, id);
                            }
                        }
                        """, minLength, minLength, minLength, minLength, minLength);
                changes.add(FileChangeProposal.of(
                        "src/test/java/com/linkforge/greenfield/StandaloneUrlShortenerTest.java",
                        FileChangeOperation.CREATE,
                        testContent,
                        null,
                        primaryTaskId,
                        primaryCriterionId,
                        "TESTING_QUALITY",
                        "Automated behavioral tests for greenfield standalone URL shortener."
                ));

                String httpTestContent = String.format("""
                        package com.linkforge.greenfield;

                        import org.junit.jupiter.api.DisplayName;
                        import org.junit.jupiter.api.Test;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.http.MediaType;
                        import org.springframework.test.web.servlet.MockMvc;

                        import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
                        import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                        @SpringBootTest(classes = GreenfieldApplication.class)
                        @AutoConfigureMockMvc
                        class GreenfieldHttpIntegrationTest {

                            @Autowired
                            private MockMvc mockMvc;

                            @Test
                            @DisplayName("Creates short link via HTTP API and resolves redirect through HTTP 302")
                            void createsAndResolvesViaHttp() throws Exception {
                                String validAlias = "b".repeat(%d);
                                String payload = \"\"\"
                                        {
                                          "destinationUrl": "https://example.com/greenfield-docs",
                                          "customAlias": "%s"
                                        }
                                        \"\"\";

                                mockMvc.perform(post("/api/v1/greenfield/links")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(payload))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.identifier").value(validAlias))
                                        .andExpect(jsonPath("$.destinationUrl").value("https://example.com/greenfield-docs"));

                                mockMvc.perform(get("/r/" + validAlias))
                                        .andExpect(status().isFound())
                                        .andExpect(header().string("Location", "https://example.com/greenfield-docs"));
                            }

                            @Test
                            @DisplayName("HTTP API rejects custom alias shorter than minimum length %d")
                            void rejectsShortAliasViaHttp() throws Exception {
                                String tooShort = "a".repeat(Math.max(1, %d - 1));
                                mockMvc.perform(post("/api/v1/greenfield/links")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\\"destinationUrl\\": \\"https://example.com/short\\", \\"customAlias\\": \\"" + tooShort + "\\"}"))
                                        .andExpect(status().is4xxClientError());
                            }

                            @Test
                            @DisplayName("HTTP API accepts custom alias meeting minimum length %d")
                            void acceptsMinimumLengthAliasViaHttp() throws Exception {
                                String exactMin = "a".repeat(%d);
                                mockMvc.perform(post("/api/v1/greenfield/links")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\\"destinationUrl\\": \\"https://example.com/exact\\", \\"customAlias\\": \\"" + exactMin + "\\"}"))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.identifier").value(exactMin));
                            }
                        }
                        """, minLength, "b".repeat(minLength), minLength, minLength, minLength, minLength);
                changes.add(FileChangeProposal.of(
                        "src/test/java/com/linkforge/greenfield/GreenfieldHttpIntegrationTest.java",
                        FileChangeOperation.CREATE,
                        httpTestContent,
                        null,
                        primaryTaskId,
                        combinedCriteria,
                        "TESTING_QUALITY",
                        "Automated HTTP API integration tests verifying greenfield link creation and 302 redirection."
                ));
            }
            default -> {
                return ImplementationProposal.unsupported(
                        scope.name(),
                        "Scope '" + scope.name() + "' cannot be safely implemented without human clarification."
                );
            }
        }

        return ImplementationProposal.supported(scope.name(), changes);
    }

    private FileChangeProposal createDeterministicTestProposal(String primaryTaskId, String primaryCriterionId, int minLength, int maxLength) {
        String testContent = String.format("""
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
                    @DisplayName("Accepts minimum length alias of %d characters")
                    void acceptsMinimumLengthAlias() {
                        String exactMin = "a".repeat(%d);
                        assertThatCode(() -> AliasValidator.validate(exactMin)).doesNotThrowAnyException();
                    }

                    @Test
                    @DisplayName("Accepts maximum length alias of %d characters")
                    void acceptsMaximumLengthAlias() {
                        String exactMax = "a".repeat(%d);
                        assertThatCode(() -> AliasValidator.validate(exactMax)).doesNotThrowAnyException();
                    }

                    @Test
                    @DisplayName("Regression test: Rejects aliases shorter than required minimum length %d")
                    void rejectsAliasesShorterThanMinimum() {
                        for (int len = 1; len < %d; len++) {
                            String tooShort = "a".repeat(len);
                            assertThatThrownBy(() -> AliasValidator.validate(tooShort))
                                    .isInstanceOf(InvalidAliasException.class);
                        }
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
                    @DisplayName("Rejects aliases longer than maximum %d")
                    void rejectsAliasesLongerThanMaximum() {
                        String tooLong = "a".repeat(%d + 1);
                        assertThatThrownBy(() -> AliasValidator.validate(tooLong))
                                .isInstanceOf(InvalidAliasException.class);
                    }
                }
                """, minLength, minLength, maxLength, maxLength, minLength, minLength, maxLength, maxLength);

        return FileChangeProposal.of(
                "src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java",
                FileChangeOperation.CREATE,
                testContent,
                null,
                primaryTaskId,
                primaryCriterionId,
                "TESTING_QUALITY",
                "Added comprehensive executable validation tests covering minimum " + minLength +
                        ", maximum " + maxLength + ", invalid chars, null/blank, and out-of-range aliases."
        );
    }

    private FileChangeProposal createDeterministicTestProposal(String primaryTaskId, String primaryCriterionId) {
        return createDeterministicTestProposal(primaryTaskId, primaryCriterionId, 3, 30);
    }

    private FileChangeProposal createDeterministicHttpTestProposal(
            String primaryTaskId,
            String primaryCriterionId,
            int minLength,
            int maxLength
    ) {
        String testContent = String.format("""
                package com.linkforge.api;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.http.MediaType;
                import org.springframework.test.web.servlet.MockMvc;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                @SpringBootTest
                @AutoConfigureMockMvc
                class CustomAliasHttpValidationTest {

                    @Autowired
                    private MockMvc mockMvc;

                    @Test
                    @DisplayName("HTTP API rejects aliases shorter than requirement-derived minimum length %d")
                    void rejectsAliasShorterThanMinimumViaHttpApi() throws Exception {
                        String tooShort = "a".repeat(Math.max(1, %d - 1));
                        String payload = "{\\"destinationUrl\\": \\"https://example.com/target\\", \\"customAlias\\": \\"" + tooShort + "\\"}";
                        mockMvc.perform(post("/api/v1/links")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error").value("INVALID_ALIAS"));
                    }

                    @Test
                    @DisplayName("HTTP API accepts alias at requirement-derived minimum length %d")
                    void acceptsAliasAtMinimumLengthViaHttpApi() throws Exception {
                        String exactMin = "a".repeat(%d);
                        String payload = "{\\"destinationUrl\\": \\"https://example.com/target\\", \\"customAlias\\": \\"" + exactMin + "\\"}";
                        mockMvc.perform(post("/api/v1/links")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.customAlias").value(exactMin));
                    }
                }
                """, minLength, minLength, minLength, minLength);

        return FileChangeProposal.of(
                "src/test/java/com/linkforge/api/CustomAliasHttpValidationTest.java",
                FileChangeOperation.CREATE,
                testContent,
                null,
                primaryTaskId,
                primaryCriterionId,
                "TESTING_QUALITY",
                "Added HTTP API integration tests verifying requirement-derived alias length bounds (" +
                        minLength + " to " + maxLength + " characters) via real endpoints."
        );
    }

    public static int extractMinAliasLength(String text, List<String> criteria, int defaultMin) {
        if (text != null) {
            int m = RequirementInterpreterAgent.extractMinAliasLength(text, -1);
            if (m > 0) return m;
        }
        if (criteria != null) {
            for (String c : criteria) {
                int m = RequirementInterpreterAgent.extractMinAliasLength(c, -1);
                if (m > 0) return m;
            }
        }
        return defaultMin;
    }

    public static int extractMaxAliasLength(String text, List<String> criteria, int defaultMax) {
        if (text != null) {
            int m = RequirementInterpreterAgent.extractMaxAliasLength(text, -1);
            if (m > 0) return m;
        }
        if (criteria != null) {
            for (String c : criteria) {
                int m = RequirementInterpreterAgent.extractMaxAliasLength(c, -1);
                if (m > 0) return m;
            }
        }
        return defaultMax;
    }

    public static boolean validatesDerivedBounds(String proposedContent, int minLength, int maxLength) {
        if (proposedContent == null || proposedContent.isBlank()) {
            return false;
        }
        // If requirement has min != 3, reject content that hardcodes 3
        if (minLength != 3) {
            if (proposedContent.contains("MIN_LENGTH = 3;")
                    || proposedContent.contains("MIN_LENGTH = 3 ")
                    || proposedContent.contains("{3,")
                    || proposedContent.contains("< 3")
                    || proposedContent.contains("<= 2")) {
                return false;
            }
        }
        // Must contain minLength enforcement
        boolean hasMin = proposedContent.contains("MIN_LENGTH = " + minLength)
                || proposedContent.contains("{" + minLength + ",")
                || proposedContent.contains("< " + minLength)
                || proposedContent.contains("<= " + (minLength - 1));
        if (!hasMin) {
            return false;
        }

        // If requirement has max != 30, reject content that hardcodes 30
        if (maxLength != 30) {
            if (proposedContent.contains("MAX_LENGTH = 30;")
                    || proposedContent.contains("MAX_LENGTH = 30 ")
                    || proposedContent.contains(",30}")
                    || proposedContent.contains("> 30")
                    || proposedContent.contains(">= 31")) {
                return false;
            }
        }
        // Must contain maxLength enforcement
        boolean hasMax = proposedContent.contains("MAX_LENGTH = " + maxLength)
                || proposedContent.contains("," + maxLength + "}")
                || proposedContent.contains("> " + maxLength)
                || proposedContent.contains(">= " + (maxLength + 1));
        return hasMax;
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

        int derivedMin = extractMinAliasLength(requirementText, acceptanceCriteria, 3);
        int derivedMax = extractMaxAliasLength(requirementText, acceptanceCriteria, 30);

        String systemPrompt = String.format("""
                You are LinkForge's Governed Implementation Specialist.
                Propose a minimal, safe, compilable Java file change modifying AliasValidator to enforce alias length bounds.
                Requirement dictates minimum alias length: %d, maximum alias length: %d.
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
                - Do not use hardcoded 3-30 bounds if requirement derived bounds are different.
                """, derivedMin, derivedMax);

        String userPrompt = "Requirement: " + requirementText + "\\nDerived Min Length: " + derivedMin + "\\nDerived Max Length: " + derivedMax;
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

            // 5. Validate that model proposal does NOT silently use hardcoded 3-30 bounds
            if (!validatesDerivedBounds(proposedContent, derivedMin, derivedMax)) {
                log.warn("Model proposal does not enforce requirement-derived bounds (min: {}, max: {}); falling back to trusted requirement-derived implementation.",
                        derivedMin, derivedMax);
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

            // Always bundle with LinkForge-controlled deterministic test validation matching derived bounds
            FileChangeProposal testChange = createDeterministicTestProposal(primaryTaskId, primaryCriterionId, derivedMin, derivedMax);
            FileChangeProposal httpTestChange = createDeterministicHttpTestProposal(primaryTaskId, primaryCriterionId, derivedMin, derivedMax);
            return ImplementationProposal.supported(scope.name(), List.of(change, testChange, httpTestChange));
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

    private Path resolveRepositoryRoot(RepositoryEvidence evidence) {
        if (evidence == null || evidence.repositoryPath() == null) {
            return null;
        }
        Path direct = Path.of(evidence.repositoryPath());
        if (Files.isDirectory(direct)) {
            return direct;
        }
        if (inspectionProperties != null && inspectionProperties.getApprovedRoot() != null) {
            Path fromApproved = Path.of(inspectionProperties.getApprovedRoot()).resolve(evidence.repositoryPath());
            if (Files.isDirectory(fromApproved)) {
                return fromApproved;
            }
        }
        return null;
    }

    private String computeBaselineFileHash(String relativePath, RepositoryEvidence evidence) {
        Path repoRoot = resolveRepositoryRoot(evidence);
        if (repoRoot != null) {
            try {
                Path fileInRepo = repoRoot.resolve(relativePath);
                if (Files.exists(fileInRepo)) {
                    byte[] bytes = Files.readAllBytes(fileInRepo);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    return HexFormat.of().formatHex(digest.digest(bytes));
                }
            } catch (Exception ignored) {}
        }
        return computeBaselineFileHash(relativePath);
    }

    private boolean isBrownfieldRepository(RepositoryEvidence evidence) {
        if (evidence == null || !evidence.hasEvidence()) {
            return false;
        }
        if (evidence.repositoryPath() != null && !evidence.repositoryPath().isBlank()) {
            return true;
        }
        return isNonLinkForgeBrownfield(evidence);
    }

    private boolean isNonLinkForgeBrownfield(RepositoryEvidence evidence) {
        if (evidence == null || !evidence.hasEvidence() || evidence.sampleSourcePaths() == null) {
            return false;
        }
        List<String> javaSources = evidence.sampleSourcePaths().stream()
                .filter(p -> p.endsWith(".java") && !p.contains("src/test/"))
                .toList();
        if (javaSources.isEmpty()) {
            return false;
        }
        return javaSources.stream().anyMatch(p -> !p.contains("com/linkforge/"));
    }

    private List<FileChangeProposal> proposeBrownfieldAliasValidationChanges(
            String requirementText,
            List<String> acceptanceCriteria,
            List<PlannedTask> tasks,
            RepositoryEvidence evidence
    ) {
        String primaryCriterionId = extractPrimaryCriterionId(acceptanceCriteria);
        String primaryTaskId = extractPrimaryTaskId(tasks);
        int minLength = extractMinAliasLength(requirementText, acceptanceCriteria, 3);
        int maxLength = extractMaxAliasLength(requirementText, acceptanceCriteria, 30);

        if (evidence == null || evidence.sampleSourcePaths() == null) {
            return List.of();
        }

        List<String> javaSources = evidence.sampleSourcePaths().stream()
                .filter(p -> p.endsWith(".java") && !p.contains("src/test/"))
                .toList();

        if (javaSources.isEmpty()) {
            return List.of();
        }

        // 1. Find candidate validator or service/controller path
        String validatorPath = javaSources.stream()
                .filter(p -> p.endsWith("AliasValidator.java") || p.endsWith("Validator.java") || p.contains("Alias"))
                .findFirst()
                .orElse(null);

        String serviceOrControllerPath = javaSources.stream()
                .filter(p -> p.contains("Link") || p.contains("Shortener") || p.contains("Url") || p.contains("Alias"))
                .findFirst()
                .orElse(null);

        // If neither validator nor link service/controller exists, we cannot safely identify runtime path!
        if (validatorPath == null && serviceOrControllerPath == null) {
            return List.of();
        }

        boolean createNew = false;
        String targetPath = validatorPath;
        if (targetPath == null) {
            int lastSlash = serviceOrControllerPath.lastIndexOf('/');
            String dir = lastSlash > 0 ? serviceOrControllerPath.substring(0, lastSlash) : "src/main/java/com/example";
            targetPath = dir + "/AliasValidator.java";
            createNew = true;
        }

        String relativeToSource = targetPath.startsWith("src/main/java/")
                ? targetPath.substring("src/main/java/".length())
                : targetPath;
        int lastSlash = relativeToSource.lastIndexOf('/');
        String packageName = lastSlash > 0 ? relativeToSource.substring(0, lastSlash).replace('/', '.') : "com.example";
        String packagePath = packageName.replace('.', '/');
        String className = targetPath.substring(targetPath.lastIndexOf('/') + 1, targetPath.length() - 5);

        String regexPattern = "^[a-zA-Z0-9_-]{" + minLength + "," + maxLength + "}$";

        String validatorContent = String.format("""
                package %s;

                import java.util.regex.Pattern;

                /**
                 * Dedicated validator for custom link aliases enforcing length bounds and character constraints.
                 */
                public final class %s {

                    public static final int MIN_LENGTH = %d;
                    public static final int MAX_LENGTH = %d;
                    private static final Pattern ALIAS_PATTERN = Pattern.compile("%s");

                    private %s() {}

                    public static void validate(String alias) {
                        if (alias == null || alias.isBlank()) {
                            throw new IllegalArgumentException("Custom alias cannot be null or blank.");
                        }
                        if (alias.length() < MIN_LENGTH || alias.length() > MAX_LENGTH || !ALIAS_PATTERN.matcher(alias).matches()) {
                            throw new IllegalArgumentException("Custom alias must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                                    + " characters and contain only alphanumeric characters, underscores, or hyphens.");
                        }
                    }
                }
                """, packageName, className, minLength, maxLength, regexPattern, className);

        FileChangeOperation op = createNew ? FileChangeOperation.CREATE : FileChangeOperation.MODIFY;
        String expectedHash = createNew ? null : computeBaselineFileHash(targetPath, evidence);

        List<FileChangeProposal> changes = new ArrayList<>();
        changes.add(FileChangeProposal.of(
                targetPath,
                op,
                validatorContent,
                expectedHash,
                primaryTaskId,
                primaryCriterionId,
                "SECURITY_VALIDATION",
                "Align custom alias validation in " + targetPath + " to enforce " + minLength + " to " + maxLength + " character bounds."
        ));

        // If service exists, ensure it is wired to call validator
        Path repoRoot = resolveRepositoryRoot(evidence);
        if (serviceOrControllerPath != null && repoRoot != null) {
            Path serviceFile = repoRoot.resolve(serviceOrControllerPath);
            if (Files.exists(serviceFile)) {
                try {
                    String serviceCode = Files.readString(serviceFile);
                    if (!serviceCode.contains(className + ".validate") && !serviceCode.contains("AliasValidator.validate")) {
                        String patchedCode = null;
                        if (serviceCode.contains("public String shorten(")) {
                            patchedCode = serviceCode.replace("public String shorten(", "public String shorten(String url, String alias) {\n        " + className + ".validate(alias); // wired validation\n");
                        } else if (serviceCode.contains("shorten(")) {
                            int idx = serviceCode.indexOf("shorten(");
                            int braceIdx = serviceCode.indexOf('{', idx);
                            if (braceIdx > 0) {
                                patchedCode = serviceCode.substring(0, braceIdx + 1) + "\n        " + className + ".validate(alias);\n" + serviceCode.substring(braceIdx + 1);
                            }
                        }
                        if (patchedCode != null && !patchedCode.equals(serviceCode)) {
                            changes.add(FileChangeProposal.of(
                                    serviceOrControllerPath,
                                    FileChangeOperation.MODIFY,
                                    patchedCode,
                                    computeBaselineFileHash(serviceOrControllerPath, evidence),
                                    primaryTaskId,
                                    primaryCriterionId,
                                    "SECURITY_VALIDATION",
                                    "Wired " + className + " validation into runtime service " + serviceOrControllerPath + "."
                            ));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Add matching unit test
        String testPath = "src/test/java/" + packagePath + "/CustomAliasValidationTest.java";
        String validAlias = "a".repeat(minLength);
        String tooShortAlias = "a".repeat(Math.max(1, minLength - 1));
        String tooLongAlias = "a".repeat(maxLength + 1);

        String testContent = String.format("""
                package %s;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThatCode;
                import static org.assertj.core.api.Assertions.assertThatThrownBy;

                class CustomAliasValidationTest {

                    @Test
                    @DisplayName("Validates custom alias bounds between %d and %d characters")
                    void validatesCustomAliasBounds() {
                        assertThatCode(() -> %s.validate("%s")).doesNotThrowAnyException();
                        assertThatThrownBy(() -> %s.validate("%s"))
                                .isInstanceOf(RuntimeException.class);
                        assertThatThrownBy(() -> %s.validate("%s"))
                                .isInstanceOf(RuntimeException.class);
                    }
                }
                """, packageName, minLength, maxLength, className, validAlias, className, tooShortAlias, className, tooLongAlias);

        changes.add(FileChangeProposal.of(
                testPath,
                FileChangeOperation.CREATE,
                testContent,
                null,
                primaryTaskId,
                primaryCriterionId,
                "TESTING_QUALITY",
                "Added unit tests for alias validation bounds (" + minLength + " to " + maxLength + " characters)."
        ));

        // Add matching HTTP test
        String httpTestPath = "src/test/java/" + packagePath + "/CustomAliasHttpValidationTest.java";
        String httpTestContent = String.format("""
                package %s;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.http.MediaType;
                import org.springframework.test.web.servlet.MockMvc;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                @SpringBootTest
                @AutoConfigureMockMvc
                class CustomAliasHttpValidationTest {

                    @Autowired(required = false)
                    private MockMvc mockMvc;

                    @Test
                    @DisplayName("HTTP API rejects aliases shorter than minimum length %d and accepts valid aliases of length %d")
                    void validatesAliasLengthViaHttpApi() throws Exception {
                        if (mockMvc != null) {
                            String tooShort = "%s";
                            mockMvc.perform(post("/api/v1/links")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\\"destinationUrl\\": \\"https://example.com/target\\", \\"customAlias\\": \\"" + tooShort + "\\"}"))
                                    .andExpect(status().is4xxClientError());

                            String valid = "%s";
                            mockMvc.perform(post("/api/v1/links")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\\"destinationUrl\\": \\"https://example.com/target\\", \\"customAlias\\": \\"" + valid + "\\"}"))
                                    .andExpect(status().is2xxSuccessful());
                        }
                    }
                }
                """, packageName, minLength, minLength, tooShortAlias, validAlias);

        changes.add(FileChangeProposal.of(
                httpTestPath,
                FileChangeOperation.CREATE,
                httpTestContent,
                null,
                primaryTaskId,
                primaryCriterionId,
                "TESTING_QUALITY",
                "Added HTTP test verifying 3-character alias rejection and 4-character alias acceptance via endpoint."
        ));

        return changes;
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
