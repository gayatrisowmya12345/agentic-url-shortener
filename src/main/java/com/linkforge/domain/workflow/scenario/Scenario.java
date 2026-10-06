package com.linkforge.domain.workflow.scenario;

/**
 * High-level scenario classification for submitted software requirements.
 */
public enum Scenario {
    /**
     * A brand-new system or service with no existing codebase or repository to inspect.
     */
    GREENFIELD,

    /**
     * A modification, refactor, extension, or bugfix for an existing repository
     * that requires verified codebase evidence.
     */
    BROWNFIELD,

    /**
     * Insufficient information or purely qualitative scope; requires clarification
     * before planning or source modifications can proceed safely.
     */
    AMBIGUOUS
}
