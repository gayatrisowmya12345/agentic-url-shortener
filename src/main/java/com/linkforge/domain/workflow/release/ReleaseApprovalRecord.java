package com.linkforge.domain.workflow.release;

import java.time.Instant;

/**
 * Record of human approval or rejection of a release-readiness outcome hash.
 */
public record ReleaseApprovalRecord(
        String decision,
        String approver,
        String outcomeHash,
        Instant approvedAt,
        String comments
) {}
