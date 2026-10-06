package com.linkforge.api.dto;

/**
 * Honest, transparent accounting of verified analysis versus unverified or unsupported capabilities.
 */
public record ExecutionVerificationStatus(
        String requirementAnalysis,
        String scenarioClassification,
        String codebaseInspection,
        String taskPlanning,
        String humanApprovalGate,
        String specialistAnalysis,
        String sourceCodeGeneration,
        String buildExecution,
        String automatedTestExecution,
        String deploymentAndRelease
) {}
