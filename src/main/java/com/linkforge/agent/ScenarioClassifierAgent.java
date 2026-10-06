package com.linkforge.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.model.StructuredScenarioClassification;
import com.linkforge.ai.provider.LlmModelProvider;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.scenario.ScenarioClassificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scenario Classification agent that categorizes submitted software requirements into
 * GREENFIELD, BROWNFIELD, or AMBIGUOUS.
 * Supports model-backed classification via LLM (e.g., Ollama) with strict JSON validation
 * and safe deterministic fallback.
 */
@Component
public class ScenarioClassifierAgent {

    private static final Logger log = LoggerFactory.getLogger(ScenarioClassifierAgent.class);

    public static final String AGENT_NAME = "scenario-classifier";
    public static final String AGENT_TYPE_DETERMINISTIC = "DETERMINISTIC_SPECIALIST";
    public static final String AGENT_TYPE_MODEL_BACKED = "MODEL_BACKED_AGENT";
    public static final String AGENT_TYPE_FALLBACK = "DETERMINISTIC_SPECIALIST_FALLBACK";

    public static final String SYSTEM_PROMPT = """
            You are an expert software engineering classifier in the LinkForge engineering workbench.
            Classify the user's software requirement into one of three distinct scenarios and return EXACTLY one complete JSON object matching this schema:
            {
              "scenario": "GREENFIELD | BROWNFIELD | AMBIGUOUS",
              "rationale": "Clear justification for the classification",
              "confidence": 0.95,
              "evidenceSignals": [
                "Signal 1",
                "Signal 2"
              ],
              "clarificationQuestions": []
            }

            STRICT FORMATTING REQUIREMENTS:
            1. Output must be exactly one raw JSON object. Start immediately with '{' and end with '}'.
            2. Do NOT use Markdown formatting, do NOT use code blocks (no ``` or ```json), and do NOT include conversational preamble, intro text, or outro text.
            3. Ensure the JSON is complete and syntactically valid with all strings, arrays, and braces properly closed.

            SCENARIO DEFINITIONS & CLASSIFICATION RULES:
            1. GREENFIELD:
               - Creating, designing, or implementing a new application, service, component, or system from scratch.
               - No existing repository, codebase, or legacy files need to be inspected or modified.
               - 'clarificationQuestions' MUST be [].

            2. BROWNFIELD:
               - Modifying, enhancing, extending, refactoring, fixing bugs in, or migrating an existing system, codebase, or repository.
               - Codebase evidence from the existing repository is required before planning or making changes.
               - 'clarificationQuestions' MUST be [].

            3. AMBIGUOUS:
               - The requirement lacks sufficient information to classify the request or safely proceed.
               - Or the requirement is purely subjective, vague, or contradictory (e.g. 'make links faster and safer', 'improve things').
               - 'clarificationQuestions' MUST contain 2 or more targeted questions asking whether this is a greenfield build or a brownfield modification to an existing repository, and requesting specific operational or functional bounds.
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
            "optimize links",
            "improve things",
            "maybe add",
            "do something"
    );

    private static final List<String> BROWNFIELD_KEYWORDS = List.of(
            "existing",
            "legacy",
            "current codebase",
            "current repo",
            "repository",
            "refactor",
            "bugfix",
            "fix bug",
            "patch",
            "upgrade",
            "migrate",
            "modify existing",
            "in this repo",
            "in our codebase",
            "codebase",
            "monorepo",
            "source code"
    );

    private final LlmModelProvider llmModelProvider;
    private final ObjectMapper objectMapper;

    public ScenarioClassifierAgent() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public ScenarioClassifierAgent(
            @Autowired(required = false) LlmModelProvider llmModelProvider,
            ObjectMapper objectMapper
    ) {
        this.llmModelProvider = llmModelProvider;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public ScenarioClassificationResult classify(String requirement) {
        return classify(requirement, null);
    }

    public ScenarioClassificationResult classify(String requirement, String repositoryPath) {
        if (llmModelProvider != null && llmModelProvider.isEnabled()) {
            return classifyWithModel(requirement, repositoryPath);
        }
        return classifyDeterministically(requirement, repositoryPath, false, null);
    }

    private ScenarioClassificationResult classifyWithModel(String requirement, String repositoryPath) {
        log.info("Executing model-backed scenario classification via provider '{}' (model: '{}')",
                llmModelProvider.getProviderId(), llmModelProvider.getModelName());

        String userPrompt = buildUserPrompt(requirement, repositoryPath);

        try {
            String rawResponse = llmModelProvider.generate(SYSTEM_PROMPT, userPrompt);
            StructuredScenarioClassification structured = parseAndValidateModelOutput(rawResponse, requirement);

            String scenarioUpper;
            if (structured.scenario() != null && !structured.scenario().isBlank()) {
                scenarioUpper = structured.scenario().toUpperCase(Locale.ROOT).trim();
            } else if (structured.summary() != null && !structured.summary().isBlank()) {
                String lower = (structured.summary() + " " + (requirement != null ? requirement : "")).toLowerCase(Locale.ROOT);
                if (lower.contains("existing") || lower.contains("legacy") || lower.contains("repository") || lower.contains("codebase")) {
                    scenarioUpper = "BROWNFIELD";
                } else {
                    scenarioUpper = "GREENFIELD";
                }
            } else {
                throw new IllegalArgumentException("Model output missing required 'scenario' field");
            }

            Scenario scenario = Scenario.valueOf(scenarioUpper);
            double confidence = structured.confidence() != null ? structured.confidence() : 0.90;
            List<String> questions = structured.clarificationQuestions();

            if (scenario == Scenario.AMBIGUOUS && (questions == null || questions.isEmpty())) {
                questions = defaultClarificationQuestions();
            }

            String rationale = structured.rationale();
            if (rationale == null || rationale.isBlank()) {
                rationale = structured.summary() != null ? structured.summary() : "Model identified scenario as " + scenario;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", AGENT_TYPE_MODEL_BACKED);
            metadata.put("agent", AGENT_NAME);
            metadata.put("provider", llmModelProvider.getProviderId());
            metadata.put("model", llmModelProvider.getModelName());
            metadata.put("fallbackOccurred", false);
            metadata.put("scenario", scenario.name());
            metadata.put("confidence", confidence);

            return new ScenarioClassificationResult(
                    scenario,
                    rationale,
                    confidence,
                    structured.evidenceSignals(),
                    questions,
                    metadata
            );
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Model-backed scenario classification failed: '{}'. Executing deterministic fallback.", reason);
            return classifyDeterministically(requirement, repositoryPath, true, reason);
        }
    }

    private String buildUserPrompt(String requirement, String repositoryPath) {
        if (repositoryPath != null && !repositoryPath.isBlank()) {
            return String.format("Requirement:\n%s\n\nSupplied Repository Path:\n%s", requirement, repositoryPath);
        }
        return "Requirement:\n" + requirement;
    }

    private StructuredScenarioClassification parseAndValidateModelOutput(String rawResponse, String requirement) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException("Model returned null or blank response");
        }

        String jsonCandidate = sanitizeJson(rawResponse);
        StructuredScenarioClassification structured;
        try {
            structured = objectMapper.readValue(jsonCandidate, StructuredScenarioClassification.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Model output failed JSON parsing: " + e.getMessage(), e);
        }

        if (structured.scenario() == null && structured.summary() == null) {
            throw new IllegalArgumentException("Model output missing required 'scenario' field");
        }

        if (structured.scenario() != null) {
            String scenarioUpper = structured.scenario().toUpperCase(Locale.ROOT).trim();
            if (!"GREENFIELD".equals(scenarioUpper) && !"BROWNFIELD".equals(scenarioUpper) && !"AMBIGUOUS".equals(scenarioUpper)) {
                throw new IllegalArgumentException("Model returned unknown scenario: '" + structured.scenario() + "'");
            }
        }

        return structured;
    }

    private String sanitizeJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    public ScenarioClassificationResult classifyDeterministically(
            String requirement,
            String repositoryPath,
            boolean fallbackOccurred,
            String fallbackReason
    ) {
        String lower = requirement != null ? requirement.toLowerCase(Locale.ROOT).trim() : "";
        List<String> evidenceSignals = new ArrayList<>();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agent", AGENT_NAME);
        metadata.put("type", fallbackOccurred ? AGENT_TYPE_FALLBACK : AGENT_TYPE_DETERMINISTIC);
        metadata.put("fallbackOccurred", fallbackOccurred);
        if (fallbackOccurred) {
            metadata.put("fallbackReason", fallbackReason);
        }

        // 1. Check for vague/ambiguous requirements
        boolean isVague = VAGUE_INDICATORS.stream().anyMatch(lower::contains);
        boolean hasActionableDomainAction = lower.contains("shorten")
                || lower.contains("short url")
                || lower.contains("redirect")
                || lower.contains("alias")
                || lower.contains("analytics")
                || lower.contains("api")
                || lower.contains("service")
                || lower.contains("build")
                || lower.contains("create")
                || lower.contains("refactor")
                || lower.contains("codebase")
                || lower.contains("repository");

        if ((isVague && !hasActionableDomainAction) || (lower.length() < 15 && !containsKeyword(lower, BROWNFIELD_KEYWORDS) && !hasActionableDomainAction)) {
            evidenceSignals.add("vague_or_insufficient_scope");
            metadata.put("scenario", Scenario.AMBIGUOUS.name());
            metadata.put("confidence", 0.85);

            return ScenarioClassificationResult.ambiguous(
                    "Requirement lacks verifiable functional bounds or clear target scope to safely proceed.",
                    0.85,
                    evidenceSignals,
                    defaultClarificationQuestions(),
                    metadata
            );
        }

        // 2. Check for brownfield indicators
        boolean hasBrownfieldKeyword = false;
        for (String kw : BROWNFIELD_KEYWORDS) {
            if (lower.contains(kw)) {
                evidenceSignals.add("keyword:" + kw);
                hasBrownfieldKeyword = true;
            }
        }

        if (repositoryPath != null && !repositoryPath.isBlank()) {
            evidenceSignals.add("repository_path_supplied");
        }

        if (hasBrownfieldKeyword || (repositoryPath != null && !repositoryPath.isBlank() && (lower.contains("modify") || lower.contains("update") || lower.contains("fix") || lower.contains("extend")))) {
            metadata.put("scenario", Scenario.BROWNFIELD.name());
            metadata.put("confidence", 0.90);

            return ScenarioClassificationResult.brownfield(
                    "Requirement involves modifying, refactoring, or extending an existing repository or codebase.",
                    0.90,
                    evidenceSignals,
                    metadata
            );
        }

        // 3. Default to greenfield
        evidenceSignals.add("new_system_declaration");
        evidenceSignals.add("no_codebase_dependency");
        metadata.put("scenario", Scenario.GREENFIELD.name());
        metadata.put("confidence", 0.90);

        return ScenarioClassificationResult.greenfield(
            "Requirement specifies building a new software capability from scratch with no existing repository dependencies.",
            0.90,
            evidenceSignals,
            metadata
        );
    }

    private boolean containsKeyword(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private List<String> defaultClarificationQuestions() {
        return List.of(
                "Is this request intended as a new greenfield build or a modification to an existing codebase?",
                "What specific functional capabilities, endpoints, or quantitative constraints should be implemented?"
        );
    }
}
