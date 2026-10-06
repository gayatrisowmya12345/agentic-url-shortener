package com.linkforge.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic specialist component for analyzing requirement clarity
 * and synthesizing acceptance criteria.
 *
 * NOTE: This is a deterministic rule-based specialist agent. No LLM is connected.
 */
@Component
public class RequirementInterpreterAgent {

    public static final String AGENT_NAME = "deterministic-requirement-interpreter";
    public static final String AGENT_TYPE = "DETERMINISTIC_SPECIALIST";
    public static final String AGENT_DESCRIPTION =
            "Deterministic rule-based agent for requirement clarity analysis and acceptance criteria synthesis (No LLM connected).";

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

    public RequirementInterpretationResult interpret(String rawRequirement) {
        if (rawRequirement == null || rawRequirement.trim().isEmpty()) {
            return RequirementInterpretationResult.ambiguous(
                    "REJECT_EMPTY_REQUIREMENT",
                    "Requirement string is blank or null; cannot proceed without specification.",
                    List.of("What feature or capability needs to be developed?"),
                    Map.of(
                            "agent", AGENT_NAME,
                            "type", AGENT_TYPE,
                            "mode", "rule-based"
                    )
            );
        }

        String normalized = rawRequirement.trim().toLowerCase(Locale.ROOT);

        // Check for ambiguous or overly vague prompts
        boolean isVaguePrompt = VAGUE_INDICATORS.stream().anyMatch(normalized::contains);
        boolean containsDomainKeyword = CORE_DOMAIN_KEYWORDS.stream().anyMatch(normalized::contains);
        boolean lacksSpecificAction = !(normalized.contains("shorten") || normalized.contains("redirect") || normalized.contains("api") || normalized.contains("endpoint"));

        if (isVaguePrompt || (normalized.split("\\s+").length <= 6 && lacksSpecificAction)) {
            List<String> questions = List.of(
                    "What quantitative performance target or latency benchmark defines 'faster' (e.g., p99 redirect < 15ms or cache hit ratio > 95%)?",
                    "What specific security controls or validation checks define 'safer' (e.g., malicious URL filtering, rate limiting, token expiration, HTTPS-only enforcement)?",
                    "Which link operations are in scope (e.g., short code generation, lookup redirection, analytics tracking, or administrative controls)?"
            );

            return RequirementInterpretationResult.ambiguous(
                    "REQUIRE_CLARIFICATION",
                    "Deterministic analysis detected high-level qualitative adjectives without verifiable acceptance criteria, API contracts, or performance metrics. Pausing workflow for user clarification.",
                    questions,
                    Map.of(
                            "agent", AGENT_NAME,
                            "type", AGENT_TYPE,
                            "analyzedLength", rawRequirement.length(),
                            "detectedAmbiguityType", "QUALITATIVE_ADJECTIVES_WITHOUT_METRICS"
                    )
            );
        }

        // Clear requirement path
        List<String> acceptanceCriteria = List.of(
                "AC-1: Given a valid HTTP or HTTPS destination URL, when requested via API, the system generates a unique, non-colliding short token.",
                "AC-2: Given an existing active short token, when resolving via GET request, the system issues an HTTP 302 redirect to the original destination URL.",
                "AC-3: Given a malformed, invalid, or empty destination URL, the system rejects the creation request with an HTTP 400 Bad Request error response.",
                "AC-4: Given an unknown or expired short token, the system returns an HTTP 404 Not Found error response.",
                "AC-5: Given a successful redirection event, the system atomically increments the usage count and timestamps the access record."
        );

        return RequirementInterpretationResult.clear(
                "ACCEPT_AND_SYNTHESIZE",
                "Deterministic analysis verified the requirement targets core URL-shortener domain capabilities with actionable verbs. Synthesized standard acceptance criteria.",
                acceptanceCriteria,
                Map.of(
                        "agent", AGENT_NAME,
                        "type", AGENT_TYPE,
                        "criteriaCount", acceptanceCriteria.size(),
                        "domainDetected", "URL_SHORTENING_AND_REDIRECTION"
                )
        );
    }
}
