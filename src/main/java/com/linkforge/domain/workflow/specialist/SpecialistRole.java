package com.linkforge.domain.workflow.specialist;

public enum SpecialistRole {
    API_BEHAVIOR("api-behavior-specialist", "API and behavior specialist"),
    DATA_PERSISTENCE("data-persistence-specialist", "Data and persistence specialist"),
    SECURITY_VALIDATION("security-validation-specialist", "Security and validation specialist"),
    TESTING_QUALITY("testing-quality-specialist", "Testing and quality specialist");

    private final String agentName;
    private final String description;

    SpecialistRole(String agentName, String description) {
        this.agentName = agentName;
        this.description = description;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getDescription() {
        return description;
    }

    public static SpecialistRole fromString(String value) {
        if (value == null || value.isBlank()) {
            return API_BEHAVIOR;
        }
        for (SpecialistRole role : values()) {
            if (role.name().equalsIgnoreCase(value) || role.agentName.equalsIgnoreCase(value)) {
                return role;
            }
        }
        return API_BEHAVIOR;
    }
}
