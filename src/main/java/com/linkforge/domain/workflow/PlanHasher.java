package com.linkforge.domain.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class PlanHasher {

    private PlanHasher() {}

    public static String computePlanHash(List<PlannedTask> tasks) {
        return computePlanHash(tasks, null);
    }

    public static String computePlanHash(List<PlannedTask> tasks, String proposalHash) {
        if ((tasks == null || tasks.isEmpty()) && (proposalHash == null || proposalHash.isBlank())) {
            return "empty-plan";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            if (tasks != null) {
                for (PlannedTask task : tasks) {
                    sb.append(task.taskId() != null ? task.taskId() : "").append(":")
                            .append(task.title() != null ? task.title() : "").append(":")
                            .append(task.description() != null ? task.description() : "").append(":")
                            .append(task.specialistRole() != null ? task.specialistRole() : "").append(":")
                            .append(String.join(",", task.dependencies()))
                            .append(";");
                }
            }
            if (proposalHash != null && !proposalHash.isBlank()) {
                sb.append("proposal:").append(proposalHash).append(";");
            }
            byte[] hashBytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
