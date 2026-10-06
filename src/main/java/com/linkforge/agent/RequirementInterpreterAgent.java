package com.linkforge.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.model.StructuredRequirementAnalysis;
import com.linkforge.ai.provider.LlmModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Requirement Interpreter Agent supporting both deterministic rule-based analysis
 * and model-backed LLM analysis (e.g., via Ollama) with strict output validation
 * and deterministic fallback.
 */
@Component
public class RequirementInterpreterAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementInterpreterAgent.class);

    public static final String AGENT_NAME = "requirement-interpreter";
    public static final String AGENT_TYPE = "DETERMINISTIC_SPECIALIST";
    public static final String AGENT_TYPE_DETERMINISTIC = AGENT_TYPE;
    public static final String AGENT_TYPE_MODEL_BACKED = "MODEL_BACKED_AGENT";
    public static final String AGENT_TYPE_FALLBACK = "DETERMINISTIC_SPECIALIST_FALLBACK";

    public static final String SYSTEM_PROMPT = """
            You are an expert requirement analysis agent in the LinkForge engineering workbench.
            Analyze the user's software requirement and return EXACTLY one complete JSON object matching this schema:
            {
              "summary": "Brief analysis of the requirement",
              "acceptanceCriteria": [
                "AC-1: Given ..., when ..., then ...",
                "AC-2: Given ..., when ..., then ..."
              ],
              "assumptions": [
                "Technical or operational assumption 1",
                "Technical or operational assumption 2"
              ],
              "clarificationQuestions": []
            }

            STRICT FORMATTING REQUIREMENTS:
            1. Output must be exactly one raw JSON object. Start immediately with '{' and end with '}'.
            2. Do NOT use Markdown formatting, do NOT use code blocks (no ``` or ```json), and do NOT include any conversational preamble, intro text, or outro text.
            3. Ensure the JSON is complete and syntactically valid with all strings, arrays, and braces properly closed.

            CAPABILITY EXTRACTION & DOMAIN RULES:
            - Extract acceptance criteria directly from the user's stated capabilities without inventing unrelated behavior or unrequested features.
            - Do NOT invent third-party analytics services (e.g., Google Analytics, Segment, Mixpanel) or external vendors unless explicitly requested. Represent click analytics strictly as internal/first-party application capabilities.
            - For URL-shortener requirements, ensure acceptance criteria cover all requested core and secondary capabilities:
              1. Link creation: Generating a unique short URL token from a valid destination URL.
              2. Custom aliases (when requested): Supporting user-defined custom aliases with validation and uniqueness checks.
              3. Redirect behavior: Resolving the short URL or custom alias to redirect (e.g., HTTP 302) to the destination URL.
              4. Click analytics (when requested): Recording redirect click events and retrieving internal click statistics.
            - Never omit foundational link creation or redirect behavior when secondary features (like aliases or analytics) are requested. Every requested capability must have corresponding acceptance criteria.

            ASSUMPTIONS & CONSTRAINTS RULES:
            - Assumptions must use ONLY details stated by the user or clearly labeled, non-binding implementation defaults (e.g., internal database storage, standard HTTP 302 redirect).
            - Do NOT invent numeric limits, per-user quotas (e.g., 'maximum of 1000 tokens per user'), rate limits, vendors, expiration times, or business rules that the user did not specify.
            - State reasonable technical defaults without imposing unrequested constraints, limits, or quotas.

            CRITICAL RULES FOR CLARIFICATION VS. ASSUMPTIONS:
            - Distinguish between genuinely ambiguous requests and actionable requests:
              * ACTIONABLE REQUEST: Specifies concrete software functions or capabilities (e.g., 'Create a short URL service with custom aliases and click analytics').
                You MUST provide concrete acceptance criteria covering each capability and state sensible technical defaults in 'assumptions'.
                DO NOT block on ordinary implementation choices, storage technology, hashing algorithms, click schema details, or analytics update frequency.
                Set 'clarificationQuestions' to [].
              * GENUINELY AMBIGUOUS REQUEST: Purely qualitative, subjective, or lacking actionable functional scope (e.g., 'make links faster and safer', 'improve link performance').
                Set 'acceptanceCriteria' to [] and provide 2 or more targeted 'clarificationQuestions' asking for quantitative targets or specific operational controls.
            """;

    private static final List<String> VAGUE_INDICATORS = List.of(
            "faster and safer",
            "safer and faster",
            "make links faster",
            "make links safer",
            "improve links",
            "better and faster",
            "make it better",
            "make it fast",
            "optimize links"
    );

    private static final List<String> CORE_DOMAIN_KEYWORDS = List.of(
            "shorten",
            "shortener",
            "shortening",
            "url",
            "redirect",
            "redirection",
            "link",
            "alias",
            "slug",
            "token"
    );

    private final LlmModelProvider modelProvider;
    private final ObjectMapper objectMapper;

    public RequirementInterpreterAgent() {
        this((LlmModelProvider) null, new ObjectMapper());
    }

    public RequirementInterpreterAgent(LlmModelProvider modelProvider) {
        this(modelProvider, new ObjectMapper());
    }

    @Autowired
    public RequirementInterpreterAgent(@Autowired(required = false) LlmModelProvider modelProvider, ObjectMapper objectMapper) {
        this.modelProvider = modelProvider;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public RequirementInterpretationResult interpret(String rawRequirement) {
        if (rawRequirement == null || rawRequirement.trim().isEmpty()) {
            return RequirementInterpretationResult.ambiguous(
                    "REJECT_EMPTY_REQUIREMENT",
                    "Requirement string is blank or null; cannot proceed without specification.",
                    List.of("What feature or capability needs to be developed?"),
                    Map.of(
                            "agent", AGENT_NAME,
                            "type", AGENT_TYPE_DETERMINISTIC,
                            "mode", "rule-based"
                    )
            );
        }

        // Check if a model provider is configured and enabled
        if (modelProvider != null && modelProvider.isEnabled()) {
            try {
                return executeModelInterpretation(rawRequirement);
            } catch (Exception e) {
                log.warn("Model-backed interpretation failed: '{}'. Executing deterministic fallback.", e.getMessage());
                return executeDeterministicFallback(rawRequirement, e.getMessage());
            }
        }

        // Pure deterministic path
        return interpretDeterministic(rawRequirement, false, null);
    }

    public static boolean isGenuinelyAmbiguous(String rawRequirement) {
        if (rawRequirement == null || rawRequirement.isBlank()) {
            return true;
        }
        String normalized = rawRequirement.trim().toLowerCase(Locale.ROOT);
        boolean isVaguePrompt = VAGUE_INDICATORS.stream().anyMatch(normalized::contains);
        boolean lacksSpecificAction = !(normalized.contains("shorten")
                || normalized.contains("short url")
                || normalized.contains("redirect")
                || normalized.contains("alias")
                || normalized.contains("analytics")
                || normalized.contains("link")
                || normalized.contains("api")
                || normalized.contains("endpoint"));
        return isVaguePrompt || (normalized.split("\\s+").length <= 6 && lacksSpecificAction);
    }

    private RequirementInterpretationResult executeModelInterpretation(String rawRequirement) throws Exception {
        String rawResponse = modelProvider.generate(SYSTEM_PROMPT, rawRequirement);
        StructuredRequirementAnalysis analysis = parseAndValidateModelOutput(rawResponse, rawRequirement);

        Map<String, Object> metadata = Map.of(
                "agent", AGENT_NAME,
                "type", AGENT_TYPE_MODEL_BACKED,
                "provider", modelProvider.getProviderId(),
                "model", modelProvider.getModelName(),
                "fallbackOccurred", false
        );

        List<String> sanitizedAssumptions = sanitizeAssumptions(analysis.assumptions(), rawRequirement);

        if (!analysis.acceptanceCriteria().isEmpty()) {
            List<String> combinedAssumptions = sanitizedAssumptions;
            if (!analysis.clarificationQuestions().isEmpty()) {
                combinedAssumptions = new java.util.ArrayList<>(sanitizedAssumptions);
                for (String q : analysis.clarificationQuestions()) {
                    combinedAssumptions.add("Design consideration: " + q);
                }
            }

            return RequirementInterpretationResult.clear(
                    "LLM_ANALYSIS_ACCEPTED",
                    analysis.summary(),
                    analysis.acceptanceCriteria(),
                    combinedAssumptions,
                    metadata
            );
        }

        return RequirementInterpretationResult.ambiguous(
                "LLM_CLARIFICATION_REQUIRED",
                analysis.summary(),
                sanitizedAssumptions,
                analysis.clarificationQuestions(),
                metadata
        );
    }

    private List<String> sanitizeAssumptions(List<String> rawAssumptions, String rawRequirement) {
        if (rawAssumptions == null || rawAssumptions.isEmpty()) {
            return List.of();
        }
        String normalizedReq = rawRequirement != null ? rawRequirement.toLowerCase(Locale.ROOT) : "";
        boolean reqMentionsLimitOrQuota = normalizedReq.contains("limit")
                || normalizedReq.contains("quota")
                || normalizedReq.contains("per user")
                || normalizedReq.contains("maximum");

        List<String> sanitized = new java.util.ArrayList<>();
        for (String assumption : rawAssumptions) {
            if (assumption == null || assumption.isBlank()) {
                continue;
            }
            String lower = assumption.toLowerCase(Locale.ROOT);
            // Exclude unrequested per-user limits, quotas, or token caps unless explicitly in requirement
            boolean isInventedPerUserQuota = !reqMentionsLimitOrQuota && (
                    lower.contains("per user") ||
                    lower.contains("tokens per user") ||
                    lower.contains("links per user") ||
                    lower.contains("user quota") ||
                    (lower.contains("maximum of") && (lower.contains("token") || lower.contains("link") || lower.contains("user")))
            );
            if (!isInventedPerUserQuota) {
                sanitized.add(assumption);
            }
        }
        return List.copyOf(sanitized);
    }

    private StructuredRequirementAnalysis parseAndValidateModelOutput(String rawOutput) throws Exception {
        return parseAndValidateModelOutput(rawOutput, null);
    }

    private StructuredRequirementAnalysis parseAndValidateModelOutput(String rawOutput, String rawRequirement) throws Exception {
        String cleaned = cleanJsonContent(rawOutput);
        StructuredRequirementAnalysis analysis;
        try {
            analysis = objectMapper.readValue(cleaned, StructuredRequirementAnalysis.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Model output could not be parsed as valid JSON: " + e.getMessage(), e);
        }

        if (analysis == null) {
            throw new IllegalArgumentException("Model output parsed to null analysis.");
        }

        if (analysis.summary() == null || analysis.summary().isBlank()) {
            throw new IllegalArgumentException("Model output validation failed: 'summary' must not be blank.");
        }

        boolean hasQuestions = !analysis.clarificationQuestions().isEmpty();
        boolean hasCriteria = !analysis.acceptanceCriteria().isEmpty();

        if (!hasQuestions && !hasCriteria) {
            throw new IllegalArgumentException("Model output validation failed: must provide either acceptanceCriteria or clarificationQuestions.");
        }

        if (hasQuestions) {
            for (String q : analysis.clarificationQuestions()) {
                if (q == null || q.isBlank()) {
                    throw new IllegalArgumentException("Model output validation failed: clarification questions cannot be blank.");
                }
            }
        }

        if (hasCriteria) {
            for (String ac : analysis.acceptanceCriteria()) {
                if (ac == null || ac.isBlank()) {
                    throw new IllegalArgumentException("Model output validation failed: acceptance criteria entries cannot be blank.");
                }
            }
        }

        if (analysis.assumptions() != null) {
            for (String a : analysis.assumptions()) {
                if (a == null || a.isBlank()) {
                    throw new IllegalArgumentException("Model output validation failed: assumption entries cannot be blank.");
                }
            }
        }

        // When a requirement specifies actionable capabilities, do not permit the model to block by
        // returning zero acceptance criteria and only clarification questions on ordinary implementation choices
        if (!hasCriteria && rawRequirement != null && !isGenuinelyAmbiguous(rawRequirement)) {
            throw new IllegalArgumentException("Model output validation failed: actionable requirement yielded no acceptance criteria and blocked on clarification instead of stating assumptions.");
        }

        return analysis;
    }

    private String cleanJsonContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Model returned empty or blank output.");
        }

        String content = raw.trim();

        // Strip markdown code block wrapper if present
        if (content.startsWith("```json")) {
            content = content.substring(7).trim();
        } else if (content.startsWith("```")) {
            content = content.substring(3).trim();
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3).trim();
        }

        // Extract outer JSON object if model included preamble or outro text
        int firstBrace = content.indexOf('{');
        int lastBrace = content.lastIndexOf('}');

        if (firstBrace == -1) {
            throw new IllegalArgumentException("Model output does not contain a JSON object (missing opening brace '{').");
        }
        if (lastBrace == -1 || lastBrace <= firstBrace) {
            throw new IllegalArgumentException("Model output is truncated or incomplete (missing closing brace '}').");
        }

        return content.substring(firstBrace, lastBrace + 1).trim();
    }

    private RequirementInterpretationResult executeDeterministicFallback(String rawRequirement, String failureReason) {
        return interpretDeterministic(rawRequirement, true, failureReason);
    }

    private RequirementInterpretationResult interpretDeterministic(String rawRequirement, boolean fallback, String fallbackReason) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agent", AGENT_NAME);
        metadata.put("type", fallback ? AGENT_TYPE_FALLBACK : AGENT_TYPE_DETERMINISTIC);
        metadata.put("fallbackOccurred", fallback);
        if (fallback) {
            metadata.put("fallbackReason", fallbackReason != null ? fallbackReason : "Unknown provider failure");
            if (modelProvider != null) {
                metadata.put("attemptedProvider", modelProvider.getProviderId());
                metadata.put("attemptedModel", modelProvider.getModelName());
            }
        }

        if (isGenuinelyAmbiguous(rawRequirement)) {
            List<String> questions = List.of(
                    "What quantitative performance target or latency benchmark defines 'faster' (e.g., p99 redirect < 15ms or cache hit ratio > 95%)?",
                    "What specific security controls or validation checks define 'safer' (e.g., malicious URL filtering, rate limiting, token expiration, HTTPS-only enforcement)?",
                    "Which link operations are in scope (e.g., short code generation, lookup redirection, analytics tracking, or administrative controls)?"
            );

            return RequirementInterpretationResult.ambiguous(
                    "REQUIRE_CLARIFICATION",
                    "Deterministic analysis detected high-level qualitative adjectives without verifiable acceptance criteria, API contracts, or performance metrics. Pausing workflow for user clarification.",
                    List.of(),
                    questions,
                    metadata
            );
        }

        List<String> acceptanceCriteria = List.of(
                "AC-1: Given a valid HTTP or HTTPS destination URL, when requested via API, the system generates a unique, non-colliding short token.",
                "AC-2: Given an existing active short token, when resolving via GET request, the system issues an HTTP 302 redirect to the original destination URL.",
                "AC-3: Given a malformed, invalid, or empty destination URL, the system rejects the creation request with an HTTP 400 Bad Request error response.",
                "AC-4: Given an unknown or expired short token, the system returns an HTTP 404 Not Found error response.",
                "AC-5: Given a successful redirection event, the system atomically increments the usage count and timestamps the access record."
        );

        List<String> assumptions = List.of(
                "Short tokens are 6 to 8 alphanumeric characters.",
                "Redirection targets are standard HTTP or HTTPS protocols.",
                "Token collisions are resolved deterministically before persistence."
        );

        return RequirementInterpretationResult.clear(
                "ACCEPT_AND_SYNTHESIZE",
                "Deterministic analysis verified the requirement targets core URL-shortener domain capabilities with actionable verbs. Synthesized standard acceptance criteria.",
                acceptanceCriteria,
                assumptions,
                metadata
        );
    }
}
