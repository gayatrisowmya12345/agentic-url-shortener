package com.linkforge.service.implementation.container;

import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;

import java.nio.file.Path;

/**
 * Interface for executing build verification inside genuine container isolation.
 * Guarantees untrusted submitted wrappers and build scripts are never executed on the host.
 */
public interface ContainerBuildExecutor {

    /**
     * Checks if genuine container isolation is actively operational and accessible.
     * Must never rely on a static configuration flag or mere binary existence alone.
     */
    boolean isAvailable();

    /**
     * Executes build verification inside a secured, resource-bounded container on the disposable workspace.
     */
    BuildValidationResult executeIsolatedBuild(Path disposableWorkspace, String buildCommand, ImplementationProposal proposal);

    /**
     * Identifies the isolation provider (e.g. "DOCKER_CONTAINER", "MOCK_CONTAINER").
     */
    String getIsolationType();
}
