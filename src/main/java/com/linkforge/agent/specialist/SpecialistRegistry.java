package com.linkforge.agent.specialist;

import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.specialist.SpecialistRole;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class SpecialistRegistry {

    private final Map<SpecialistRole, SpecialistAgent> agentMap = new EnumMap<>(SpecialistRole.class);

    public SpecialistRegistry(List<SpecialistAgent> agents) {
        for (SpecialistAgent agent : agents) {
            agentMap.put(agent.getRole(), agent);
        }
    }

    public SpecialistAgent getAgent(SpecialistRole role) {
        SpecialistAgent agent = agentMap.get(role);
        if (agent == null) {
            return agentMap.get(SpecialistRole.API_BEHAVIOR);
        }
        return agent;
    }

    public SpecialistAgent resolveAgentForTask(PlannedTask task) {
        if (task.specialistRole() != null && !task.specialistRole().isBlank()) {
            SpecialistRole role = SpecialistRole.fromString(task.specialistRole());
            return getAgent(role);
        }

        String text = (task.title() + " " + task.description()).toLowerCase();
        if (text.contains("test") || text.contains("verify") || text.contains("quality")) {
            return getAgent(SpecialistRole.TESTING_QUALITY);
        }
        if (text.contains("security") || text.contains("validation") || text.contains("sanitization") || text.contains("scheme")) {
            return getAgent(SpecialistRole.SECURITY_VALIDATION);
        }
        if (text.contains("model") || text.contains("store") || text.contains("database") || text.contains("h2") || text.contains("persistence") || text.contains("schema")) {
            return getAgent(SpecialistRole.DATA_PERSISTENCE);
        }
        return getAgent(SpecialistRole.API_BEHAVIOR);
    }
}
