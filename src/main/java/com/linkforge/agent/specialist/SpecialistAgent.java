package com.linkforge.agent.specialist;

import com.linkforge.domain.workflow.specialist.SpecialistRole;
import com.linkforge.domain.workflow.specialist.SpecialistTaskInput;
import com.linkforge.domain.workflow.specialist.SpecialistTaskResult;

public interface SpecialistAgent {
    SpecialistRole getRole();
    String getAgentName();
    SpecialistTaskResult execute(SpecialistTaskInput input);
}
