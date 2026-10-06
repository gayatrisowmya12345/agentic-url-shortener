package com.linkforge.domain.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class PlanHasher {

    private PlanHasher() {}

    public static String computePlanHash(List<PlannedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "empty-plan";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (PlannedTask task : tasks) {
                sb.append(task.taskId() != null ? task.taskId() : "").append(":")
                        .append(task.title() != null ? task.title() : "").append(":")
                        .append(task.description() != null ? task.description() : "").append(":")
                        .append(task.specialistRole() != null ? task.specialistRole() : "").append(":")
                        .append(String.join(",", task.dependencies()))
                        .append(";");
            }
            byte[] hashBytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
