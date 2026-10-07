package com.linkforge.agent.specialist;

import com.linkforge.domain.workflow.implementation.BuildDiagnosisResult;
import com.linkforge.domain.workflow.implementation.FileChangeOperation;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RepairProposal;
import com.linkforge.service.implementation.GovernedPatchApplier;
import com.linkforge.service.implementation.ImplementationProposerAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Specialist agent that creates structured, bounded repair patch proposals with
 * cryptographic hashes, lineage, and strict safety validation.
 */
@Component
public class ImplementationRepairSpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(ImplementationRepairSpecialistAgent.class);

    private static final Set<String> ALLOWED_REPAIR_PATHS = Set.of(
            ImplementationProposerAgent.APPROVED_ALIAS_VALIDATOR_PATH,
            "src/main/java/com/linkforge/service/link/LinkShortenerService.java",
            "src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java",
            "src/test/java/com/linkforge/api/CustomAliasHttpValidationTest.java"
    );

    public RepairProposal createRepairProposal(
            BuildDiagnosisResult diagnosis,
            ImplementationProposal previousProposal,
            String requirementText,
            List<String> acceptanceCriteria,
            int attemptNumber
    ) {
        return createRepairProposal(diagnosis, previousProposal, requirementText, acceptanceCriteria, attemptNumber, null);
    }

    public RepairProposal createRepairProposal(
            BuildDiagnosisResult diagnosis,
            ImplementationProposal previousProposal,
            String requirementText,
            List<String> acceptanceCriteria,
            int attemptNumber,
            java.nio.file.Path workspaceRoot
    ) {
        String repairId = UUID.randomUUID().toString();
        String lineageHash = previousProposal != null ? previousProposal.proposalHash() : "";

        if (diagnosis == null || !diagnosis.repairable()) {
            return new RepairProposal(
                    repairId,
                    attemptNumber,
                    lineageHash,
                    "",
                    List.of(),
                    "Diagnosis indicated issue is not automatically repairable.",
                    false,
                    "Diagnosis marked unrepairable.",
                    Instant.now()
            );
        }

        List<FileChangeProposal> repairChanges = new ArrayList<>();
        String rationale;

        // Check if diagnosis indicates alias validation or compilation issue
        boolean isAliasIssue = diagnosis.affectedFiles().stream()
                .anyMatch(f -> f.contains("AliasValidator") || f.contains("CustomAliasValidationTest") || f.contains("CustomAliasHttpValidationTest"))
                || diagnosis.likelyCause().toLowerCase().contains("alias")
                || (diagnosis.affectedFiles().isEmpty() && diagnosis.likelyCause().toLowerCase().contains("compilation"));

        if (isAliasIssue) {
            // Determine required bounds from criteria / requirement
            int minLength = ImplementationProposerAgent.extractMinAliasLength(requirementText, acceptanceCriteria, 4);
            int maxLength = ImplementationProposerAgent.extractMaxAliasLength(requirementText, acceptanceCriteria, 30);

            String repairedAliasValidatorContent = String.format("""
                    package com.linkforge.service.link;

                    import com.linkforge.domain.link.exception.InvalidAliasException;
                    import java.util.regex.Pattern;

                    /**
                     * Dedicated validator for custom link aliases enforcing length bounds and character constraints.
                     * Repaired by ImplementationRepairSpecialistAgent (attempt %d).
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
                    """, attemptNumber, minLength, maxLength, minLength, maxLength);

            String expectedHash = null;
            if (workspaceRoot != null) {
                java.nio.file.Path p = workspaceRoot.resolve(ImplementationProposerAgent.APPROVED_ALIAS_VALIDATOR_PATH);
                if (java.nio.file.Files.exists(p)) {
                    try {
                        expectedHash = GovernedPatchApplier.computeSha256(java.nio.file.Files.readAllBytes(p));
                    } catch (Exception ignored) {}
                }
            }
            if (expectedHash == null && previousProposal != null) {
                for (FileChangeProposal fcp : previousProposal.changes()) {
                    if (ImplementationProposerAgent.APPROVED_ALIAS_VALIDATOR_PATH.equals(fcp.path())) {
                        expectedHash = fcp.targetHash();
                        break;
                    }
                }
            }
            if (expectedHash == null) {
                expectedHash = "0000000000000000000000000000000000000000000000000000000000000000";
            }

            repairChanges.add(FileChangeProposal.of(
                    ImplementationProposerAgent.APPROVED_ALIAS_VALIDATOR_PATH,
                    FileChangeOperation.MODIFY,
                    repairedAliasValidatorContent,
                    expectedHash,
                    "TASK-REPAIR-" + attemptNumber,
                    "AC-ALIAS",
                    "SECURITY_VALIDATION",
                    "Repaired AliasValidator to enforce exact minimum length " + minLength + " and maximum length " + maxLength + "."
            ));

            rationale = "Repaired AliasValidator boundary constraints to satisfy minimum length " + minLength +
                    " requirement and reject out-of-range aliases.";
        } else {
            // Generic repair: never simply replay the same failed patch unchanged
            boolean canCorrect = false;
            if (previousProposal != null) {
                for (FileChangeProposal original : previousProposal.changes()) {
                    if (diagnosis.affectedFiles().contains(original.path())) {
                        String originalContent = original.proposedContent();
                        String correctedContent = originalContent;
                        if (diagnosis.likelyCause().toLowerCase().contains("syntax") || diagnosis.likelyCause().toLowerCase().contains("compilation")) {
                            correctedContent = originalContent.replace(";;", ";");
                        }
                        if (correctedContent.equals(originalContent)) {
                            log.warn("Generic repair refuses to simply replay failed patch unchanged for {}", original.path());
                            continue;
                        }
                        String expectedHash = null;
                        if (workspaceRoot != null) {
                            java.nio.file.Path p = workspaceRoot.resolve(original.path());
                            if (java.nio.file.Files.exists(p)) {
                                try {
                                    expectedHash = GovernedPatchApplier.computeSha256(java.nio.file.Files.readAllBytes(p));
                                } catch (Exception ignored) {}
                            }
                        }
                        if (expectedHash == null) {
                            expectedHash = original.targetHash();
                        }
                        repairChanges.add(FileChangeProposal.of(
                                original.path(),
                                original.operation(),
                                correctedContent,
                                expectedHash,
                                "TASK-REPAIR-" + attemptNumber,
                                "AC-1",
                                original.specialistRole(),
                                "Genuinely corrective repair for " + original.path() + " resolving diagnosed failure."
                        ));
                        canCorrect = true;
                    }
                }
            }
            if (!canCorrect || repairChanges.isEmpty()) {
                return new RepairProposal(
                        repairId,
                        attemptNumber,
                        lineageHash,
                        "",
                        List.of(),
                        "Automated repair halted: cannot safely repair without replaying identical failed patch.",
                        false,
                        "Generic repair refused to replay identical failed patch without corrective transformation.",
                        Instant.now()
                );
            }
            rationale = "Applied genuinely corrective adjustments to affected files identified in diagnosis.";
        }

        // Safety policy validation: ensure no out-of-scope paths or malicious operations
        boolean safe = true;
        String unsafeReason = null;

        for (FileChangeProposal change : repairChanges) {
            String path = change.path();
            if (!ALLOWED_REPAIR_PATHS.contains(path)) {
                safe = false;
                unsafeReason = "Repair change path '" + path + "' is not in allowed repair paths.";
                break;
            }
            if (path.contains("pom.xml") || path.contains("mvnw") || path.endsWith(".xml")) {
                safe = false;
                unsafeReason = "Repair change attempted to modify forbidden project configuration: " + path;
                break;
            }
        }

        String repairHash = RepairProposal.computeHash(lineageHash, attemptNumber, repairChanges);

        log.info("Created repair proposal {} (attempt {}): safe={}, hash={}, changes={}",
                repairId, attemptNumber, safe, repairHash, repairChanges.size());

        return new RepairProposal(
                repairId,
                attemptNumber,
                lineageHash,
                repairHash,
                repairChanges,
                rationale,
                safe,
                unsafeReason,
                Instant.now()
        );
    }
}
