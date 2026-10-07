# LinkForge

A governed multi-agent software engineering workbench demonstrated through an enterprise-grade URL shortener.

LinkForge combines specialized AI agents with deterministic safety guards, bounded codebase inspection, topological task graph coordination, and transactional data persistence.

---

## Architecture & Workflows

### Scenario-Aware Workflow Pipeline
LinkForge dynamically classifies incoming software engineering requirements into one of three distinct scenarios before decomposing tasks:

1. **GREENFIELD**:
   - The requirement defines a new system or capability built from scratch.
   - Plannable without any repository path.
   - Does not claim repository findings or depend on codebase evidence.

2. **BROWNFIELD**:
   - The requirement targets an existing codebase or repository (e.g., refactoring, extending, fixing bugs, or migrating).
   - Requires a valid `repositoryPath` relative to the approved root.
   - Performs safe, read-only codebase inspection and grounds acceptance criteria and task planning strictly in verified codebase evidence.

3. **AMBIGUOUS**:
   - The requirement lacks sufficient information, verifiable bounds, or operational metrics to safely proceed.
   - Pauses the workflow in `WAITING_FOR_CLARIFICATION` with targeted questions.
   - Blocks plan generation and specialist coordination until clarified.

---

### Specialist Agents & Coordinated Task Planning

Following task graph decomposition, LinkForge coordinates focused domain specialists to analyze, validate, and plan each discrete task:

#### Focused Specialist Roles
- **API & Behavior Specialist (`api-behavior-specialist`)**:
  Analyzes RESTful contracts, endpoint structures, HTTP status codes (201 Created, 302 Found, 400 Bad Request, 404 Not Found, 409 Conflict), and user-facing routing.
- **Data & Persistence Specialist (`data-persistence-specialist`)**:
  Analyzes relational schemas (H2 tables, constraints), thread safety, atomic click counter increments, and transactional consistency.
- **Security & Validation Specialist (`security-validation-specialist`)**:
  Analyzes protocol whitelists (HTTP/HTTPS only, rejecting file/javascript schemes), RFC 3986 format validation, and alias sanitization bounds.
- **Testing & Quality Specialist (`testing-quality-specialist`)**:
  Formulates automated test strategies, MockMvc slice tests, negative edge cases, concurrent access tests, and regression matrix coverage.

#### Coordination Guarantees
- **Strict Dependency Gating**: Tasks execute in topological waves; no task begins until all prerequisite tasks have succeeded.
- **Configurable Concurrency**: Independent tasks with satisfied dependencies execute concurrently bounded by a configurable worker pool.
- **Cycle & Dependency Validation**: Detects and rejects graph cycles and unknown prerequisite dependencies before execution begins.
- **Bounded Resources**: Applies per-task execution timeouts, maximum agent invocation limits, and maximum output character bounds.
- **Partial Failure Containment**: If a task fails, dependent downstream tasks are marked as skipped, while completed independent tasks retain their results.
- **Deterministic Ordering**: Response payloads present tasks and specialist invocations in deterministic plan order regardless of parallel completion timing.
- **Model Fallback**: Model outputs are treated as untrusted data and strictly validated; provider timeouts or invalid outputs safely trigger deterministic rules.

---

### Safe, Read-Only Codebase Inspection
For brownfield requests, LinkForge inspects the target repository with rigorous security constraints:
- **Approved Root Boundary**: Rejects paths escaping the configured approved root (`linkforge.inspection.approved-root`).
- **Path Traversal Prevention**: Resolves canonical real paths and blocks `../` traversal escapes.
- **Symlink Escape Confinement**: Inspects directory and file symbolic links, rejecting any link pointing outside the repository boundary.
- **File Type Filtering**: Inspects text and source manifests (`.java`, `.py`, `.ts`, `.xml`, `.json`, `.sql`, etc.) while rejecting dangerous binaries, archives, and executables (`.exe`, `.jar`, `.so`, `.bin`, `.sh`, `.bat`).
- **Configurable Resource Limits**: Enforces strict caps on maximum file count and total repository size to prevent resource exhaustion.
- **Zero Execution Policy**: Inspects files using read-only streams. Does not execute repository code, build scripts, shell commands, or external processes.

---

---

### Requirement Clarification & Human Approval Gates

LinkForge enforces governance gates at key transition points in the workflow:

1. **Clarification Gate (`WAITING_FOR_CLARIFICATION`)**:
   - If requirement analysis detects ambiguity or unanswered questions, workflow execution pauses immediately.
   - Final task planning and specialist coordination are blocked while clarification is pending.
   - An operator submits clarification with submitter identity (`submittedBy`).
   - Blank or incomplete clarification attempts are rejected.
   - The workflow re-runs requirement analysis against the cumulative requirement history; it resumes only once requirements are sufficiently clear.

2. **Human Plan Approval Gate (`WAITING_FOR_APPROVAL`)**:
   - Once a clear requirement generates a task plan, execution pauses before specialist coordination.
   - A cryptographic SHA-256 `planHash` is computed over task identifiers, titles, descriptions, roles, and dependency edges.
   - An authorized human approver submits a decision (`APPROVED` or `REJECTED`), their identity (`approver`), comments, and the exact `planHash` reviewed.
   - Submissions with missing, mismatched, or stale hashes are rejected.
   - If rejected, the workflow transitions to `REJECTED` and halts execution.
   - If the task plan changes after an approval, the prior approval is immediately invalidated and a new approval is required.
   - Duplicate or repeat submissions are handled safely and idempotently without state corruption.

---

### Bounded Failure Recovery & Safe Stop (Cancellation)

LinkForge provides resilient stage execution and graceful workflow termination:

1. **Bounded Exponential Backoff & Transient Retries**:
   - Stage executions are guarded with bounded retry policies configured via `linkforge.workflow.retry.*`.
   - Failures are classified explicitly into `TRANSIENT` (e.g., model rate limits, transient network I/O, connect timeouts) and `NON_RETRYABLE` (e.g., invalid inputs, path traversal, security failures, plan rejection).
   - Only eligible transient failures are retried using bounded exponential backoff (`delay = min(initial * multiplier^(attempt-1), max)`). Non-retryable errors fail immediately without retry loops.
   - Completed specialist work and side effects are preserved across retries—previously succeeded tasks are not re-executed.
   - Every retry attempt, failure classification, delay, and outcome is recorded in workflow audit history.
   - When configured attempts are exhausted, the workflow transitions to `FAILED` with actionable diagnosis.

2. **Authorized Safe Stop & Workflow Cancellation**:
   - Workflows can be safely stopped at any phase: before work starts (`INITIALIZED`), while awaiting clarification or approval, and during active multi-agent execution.
   - Protected by authorization token verification (`X-Auth-Token` or `Authorization: Bearer <token>`).
   - Stop requests are idempotent; repeat cancellations on already-cancelled runs return the current state safely.
   - Once accepted, no new work or specialist tasks are dispatched. In-flight worker threads are cleanly interrupted and shut down.
   - The workflow moves to the terminal `CANCELLED` status, which permanently blocks any subsequent execution, clarification, or plan approval attempts.
   - Stop requests against already completed, failed, or rejected workflows are rejected with HTTP 409 Conflict.
   - The cancellation identity (`cancelledBy`), timestamp, reason, and execution stage are permanently recorded in audit history.

3. **Workflow Evidence, Audit History & Operator Observability**:
   - Every state transition, operator gate action, retry attempt, and agent decision is persisted to relational H2 tables (`workflow_runs`, `workflow_events`, `workflow_agent_decisions`) with monotonic timestamps, stages, and structured details.
   - Database-backed persistence ensures workflow runs and complete audit logs survive application restarts.
   - Full acceptance-criteria traceability: links each criterion (e.g. `AC-1`) to planned tasks, specialist roles, agent decisions, and audit events, while reporting uncovered criteria as `UNCOVERED`.
   - Honest capability accounting: explicitly distinguishes verified requirements and specialist analysis from runtime capabilities. For unexecuted or plan-only workflows, source generation and build execution are reported as `NOT_SUPPORTED`, and automated test execution is reported as `UNVERIFIED`. Following successful Milestone 10 governed execution, they are upgraded to `VERIFIED (ISOLATED_PROPOSAL)`, `VERIFIED (MAVEN_WRAPPER_BUILD)`, and `VERIFIED (TARGETED_TEST_EXECUTION)`. Non-success outcomes (e.g. `BLOCKED`, `TIMED_OUT`, `FAILED`, `ROLLED_BACK`) remain clearly distinct, and production deployment remains `NOT_SUPPORTED`.
   - Read-only operator observability APIs (`/history`, `/evidence`, `/summary`) protected by token authorization (`X-Operator-Token`, `X-Auth-Token`, or `Authorization: Bearer <token>`).
   - Secret-redaction safeguards: tokens, credentials, full LLM prompts, and raw secret payloads are strictly excluded from audit events and API responses.

4. **Repeatable Scenario Runs & End-to-End Workbench Validation**:
   - Repeatable automated scenarios exercise the entire multi-agent pipeline through public HTTP endpoints with isolated, disposable fixtures.
   - **Scenario 1 (Greenfield URL Shortener)**: Requirement interpretation, greenfield classification, dependency-aware DAG task synthesis, human plan approval, concurrent specialist coordination, and audit trail generation.
   - **Scenario 2 (Ambiguous Requirement & Clarification Gate)**: Ambiguity detection, targeted clarification questions, operator clarification submission, revision tracking (revision 2), and pipeline continuation.
   - **Scenario 3 (Brownfield Repository Inspection)**: Safe read-only inspection of disposable repository fixture within approved root, language/framework fingerprinting, and backward-compatible task planning without exposing local host paths.
   - **Scenario 4 (Human Approval Governance Gate)**: Human-in-the-loop plan review, SHA-256 plan hash tamper protection, rejection handling with terminal lock, and token-based authorization.
   - **Scenario 5 (Transient Failure Recovery & Safe Stop)**: Demonstrates automatic bounded retry recovery from a transient stage failure (5a, asserting retry attempts, failure classification, and success events) as well as operator safe stop (5b, immediate cancellation, blocking subsequent actions, and idempotent responses).
   - **Scenario 6 (Optional Live Ollama Execution)**: Seamless support for live model-backed analysis when local Ollama is available, with automatic graceful skip when offline.

5. **Governed Implementation & Real Build Validation (Milestone 10)**:
   - Extends the workbench with an end-to-end governed implementation pipeline that safely translates requirements into verifiable code modifications.
   - **Narrowly Supported URL-Shortener Scopes**:
     - `ALIAS_VALIDATION`: Custom alias format constraints, length boundaries, character whitelist enforcement, and collision management.
     - `DOMAIN_RESTRICTION`: Allowed and prohibited destination domain filtering, protocol whitelist, and loopback/internal IP blocking.
     - `TOKEN_POLICY`: Short token generation algorithms (Base62, alphanumeric), length configuration, and collision-retry policies.
     - `CLICK_ANALYTICS`: Click tracking data models, timestamped access events, and aggregation query strategies.
     - `URL_SHORTENER_CORE`: Core shortening service, in-memory/relational persistence, and 302 redirection handling.
     - Any request falling outside these supported scopes is safely rejected or paused (`BLOCKED`), preventing uncontrolled mutation.
   - **Strict Human Approval Gate**:
     - Any proposed file mutation requires human approval. Missing, rejected, stale, or mismatched approvals permanently prevent code modification.
     - The plan hash incorporates both the planned task graph and the implementation proposal hash. Any downstream tampering invalidates approval.
   - **Model Safety & Schema Enforcement**:
     - AI model proposals (or deterministic offline fallbacks) are parsed against strict JSON schemas.
     - Model outputs must NEVER execute shell commands or directly mutate local filesystem files.
     - Forbidden system calls (such as `Runtime.getRuntime().exec` or `ProcessBuilder`) and path traversal sequences (`../`, leading `/`) are immediately rejected.
   - **Isolated Disposable Workspace**:
     - All patch operations execute exclusively within an isolated, disposable temporary directory (`Files.createTempDirectory`). Never touches arbitrary caller-supplied paths.
     - **Path Containment**: Relative paths are resolved against the isolated root; attempts to escape via symlinks or relative navigation are rejected.
     - **Extension Whitelist**: Only allowed safe extensions (`.java`, `.xml`, `.properties`, `.json`, `.md`) are permitted. Executables, scripts, and archives are strictly forbidden.
     - **Resource Caps**: Enforces hard caps on operation count (&le; 10 files), individual file size (&le; 512 KB), and total operation size (&le; 2 MB).
     - **Duplicate Path Prevention**: Proposals with repeated file paths are rejected before application begins.
     - **Optimistic Concurrency & Stale Hash Checks**: When modifying or deleting existing files, the caller's expected input SHA-256 hash is checked against current file content. If stale, mutation halts.
     - **Atomic Swap Writes**: Files are written to temporary swap files and atomically renamed.
     - **Verified Rollback**: If build validation fails, workspace files are restored from pre-application snapshots and verified against original hashes before entering the terminal `ROLLED_BACK` state.
   - **Fixed Maven Wrapper Build Verification**:
     - The child process executes only a fixed command: `./mvnw --batch-mode test -Dtest=CustomAliasValidationTest`. Arbitrary commands are completely rejected.
     - **Sanitized Execution Environment**: The child process runs in a scrubbed environment stripped of credentials, tokens, Ollama settings, and API keys.
     - **Bounded Execution & Output**: Build execution is bounded by timeout (60 seconds) and captured output is capped (10,000 characters) to prevent memory exhaustion.
     - Persists exit code, duration, bounded output, and validation outcome in relational audit logs.

---

## Workflow Lifecycle & States

### Workflow States (`WorkflowStatus`)
- `INITIALIZED`: Workflow instance created.
- `IN_PROGRESS`: Actively executing analysis or coordination.
- `WAITING_FOR_CLARIFICATION`: Paused awaiting operator clarification.
- `WAITING_FOR_APPROVAL`: Plan or proposal generated; paused awaiting human plan approval.
- `PROPOSED`: Implementation changes synthesized; awaiting approval.
- `APPROVED`: Human approval verified with matching cryptographic plan and proposal hash.
- `APPLYING`: Atomic patches being verified and applied in isolated disposable workspace.
- `VALIDATING`: Fixed Maven Wrapper test execution (`./mvnw --batch-mode test -Dtest=CustomAliasValidationTest`) executing in child process.
- `REJECTED`: Plan or proposal rejected by human approver; execution terminated.
- `COMPLETED`: Implementation applied and verified by build validation successfully.
- `FAILED`: Execution terminated due to unrecoverable error or build failure.
- `ROLLED_BACK`: Validation failed; workspace changes reverted and verified before entering terminal state.
- `BLOCKED`: Request falls outside narrowly supported implementation scopes or violates safety policies.
- `CANCELLED`: Execution safely stopped; terminal state blocking subsequent transitions or resume.

### Workflow Stages (`WorkflowStage`)
- `REQUIREMENT_ANALYSIS`: Requirement interpretation and criteria extraction.
- `SCENARIO_CLASSIFICATION`: Greenfield vs. brownfield vs. ambiguous classification.
- `CODEBASE_INSPECTION`: Safe, read-only evidence gathering for brownfield workflows.
- `TASK_PLANNING`: Dependency-aware task graph synthesis and plan hash computation.
- `PLAN_APPROVAL`: Human governance gate prior to specialist dispatch or source mutation.
- `SPECIALIST_COORDINATION`: Concurrent topological execution of specialist agents.
- `IMPLEMENTATION_PROPOSAL`: Specialist agent generation of structured implementation proposals.
- `IMPLEMENTATION_APPLICATION`: Isolated disposable workspace patching with containment and atomic writes.
- `BUILD_VALIDATION`: Fixed Maven Wrapper build execution in sanitized environment.
- `ROLLED_BACK`: Rollback execution restoring workspace to pristine state.
- `BLOCKED`: Workflow blocked due to unsupported scope or safety violation.
- `FINISHED`: Terminal stage following completion, rollback, or rejection.


---

## Configuration Reference

### Workflow Security & Governance Gates (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.workflow.security.enabled` | `LINKFORGE_WORKFLOW_SECURITY_ENABLED` | `false` | Enable authorization token checks for clarification, approval, and cancellation endpoints |
| `linkforge.workflow.security.clarification-token` | `LINKFORGE_WORKFLOW_CLARIFICATION_TOKEN` | `dev-clarification-token` | Shared secret token required for clarification submission |
| `linkforge.workflow.security.approval-token` | `LINKFORGE_WORKFLOW_APPROVAL_TOKEN` | `dev-approval-token` | Shared secret token required for human plan approval submission |
| `linkforge.workflow.security.cancellation-token` | `LINKFORGE_WORKFLOW_CANCELLATION_TOKEN` | `dev-cancellation-token` | Shared secret token required for workflow cancellation/stop |
| `linkforge.workflow.security.operator-token` | `LINKFORGE_WORKFLOW_OPERATOR_TOKEN` | `dev-operator-token` | Shared secret token required for read-only operator observability endpoints |
| `linkforge.workflow.security.default-canceller` | `LINKFORGE_WORKFLOW_DEFAULT_CANCELLER` | `operator` | Fallback canceller identifier when not explicitly provided |

### Bounded Retry & Failure Recovery (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.workflow.retry.enabled` | `LINKFORGE_WORKFLOW_RETRY_ENABLED` | `true` | Enable bounded retries for transient stage failures |
| `linkforge.workflow.retry.max-attempts` | `LINKFORGE_WORKFLOW_RETRY_MAX_ATTEMPTS` | `3` | Maximum execution attempts for transient errors before failing |
| `linkforge.workflow.retry.initial-backoff-ms` | `LINKFORGE_WORKFLOW_RETRY_INITIAL_BACKOFF_MS` | `500` | Initial backoff delay in milliseconds |
| `linkforge.workflow.retry.max-backoff-ms` | `LINKFORGE_WORKFLOW_RETRY_MAX_BACKOFF_MS` | `5000` | Maximum cap on exponential backoff delay in milliseconds |
| `linkforge.workflow.retry.backoff-multiplier` | `LINKFORGE_WORKFLOW_RETRY_BACKOFF_MULTIPLIER` | `2.0` | Exponential backoff multiplier per retry attempt |

### Specialist Coordination (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.coordination.max-concurrency` | `LINKFORGE_COORDINATION_MAX_CONCURRENCY` | `2` | Maximum concurrent specialist task executions |
| `linkforge.coordination.task-timeout-seconds` | `LINKFORGE_COORDINATION_TASK_TIMEOUT_SECONDS` | `10` | Timeout per specialist execution in seconds |
| `linkforge.coordination.max-invocations` | `LINKFORGE_COORDINATION_MAX_INVOCATIONS` | `20` | Maximum allowable specialist invocations per workflow run |
| `linkforge.coordination.max-output-chars` | `LINKFORGE_COORDINATION_MAX_OUTPUT_CHARS` | `4000` | Hard character limit on the complete serialized SpecialistInvocation returned by the coordinator, bounding all variable-length fields (inputSummary, outputSummary, recommendations, testIdeas, addressedCriteria, provider, model, and fallbackReason) |

### Codebase Inspection (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.inspection.approved-root` | `LINKFORGE_INSPECTION_APPROVED_ROOT` | `${user.dir}` | Base directory boundary for brownfield inspections (defaults to repository root for local development) |
| `linkforge.inspection.max-file-count` | `LINKFORGE_INSPECTION_MAX_FILE_COUNT` | `500` | Maximum allowable files within inspected repository |
| `linkforge.inspection.max-total-size-bytes`| `LINKFORGE_INSPECTION_MAX_TOTAL_SIZE_BYTES` | `10485760` (10 MB) | Maximum allowable total repository byte size |
| `linkforge.inspection.max-file-size-bytes` | `LINKFORGE_INSPECTION_MAX_FILE_SIZE_BYTES` | `524288` (512 KB) | Maximum allowable size per individual file |
| `linkforge.inspection.max-snippet-lines` | `LINKFORGE_INSPECTION_MAX_SNIPPET_LINES` | `40` | Maximum lines captured per key snippet |
| `linkforge.inspection.max-evidence-files` | `LINKFORGE_INSPECTION_MAX_EVIDENCE_FILES` | `30` | Maximum relative source paths recorded in evidence |

### AI Model Provider (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.ai.enabled` | `LINKFORGE_AI_ENABLED` | `false` | Enable model-backed agent calls (defaults to deterministic fallback) |
| `linkforge.ai.provider` | `LINKFORGE_AI_PROVIDER` | `ollama` | Provider identifier (`ollama`, `fake`) |
| `linkforge.ai.model` | `LINKFORGE_AI_MODEL` | `llama3.2` | Model name |
| `linkforge.ai.base-url` | `LINKFORGE_AI_BASE_URL` | `http://localhost:11434` | Ollama service endpoint |
| `linkforge.ai.timeout-seconds` | `LINKFORGE_AI_TIMEOUT_SECONDS` | `30` | Network request timeout for LLM provider |

### Governed Execution & Build Validation (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.execution.enabled` | `LINKFORGE_EXECUTION_ENABLED` | `true` | Enable governed implementation and build validation stage |
| `linkforge.execution.max-operations` | `LINKFORGE_EXECUTION_MAX_OPERATIONS` | `10` | Maximum number of file change operations per implementation proposal |
| `linkforge.execution.max-file-bytes` | `LINKFORGE_EXECUTION_MAX_FILE_BYTES` | `524288` (512 KB) | Maximum allowable byte size for an individual proposed file |
| `linkforge.execution.max-total-bytes` | `LINKFORGE_EXECUTION_MAX_TOTAL_BYTES` | `2097152` (2 MB) | Maximum allowable total byte size across all proposed files |
| `linkforge.execution.build-timeout-seconds` | `LINKFORGE_EXECUTION_BUILD_TIMEOUT_SECONDS` | `60` | Maximum execution duration for child process build verification |
| `linkforge.execution.max-output-chars` | `LINKFORGE_EXECUTION_MAX_OUTPUT_CHARS` | `10000` | Maximum captured characters of build process stdout/stderr output |
| `linkforge.execution.build-command` | `LINKFORGE_EXECUTION_BUILD_COMMAND` | `./mvnw --batch-mode test -Dtest=CustomAliasValidationTest` | Fixed Maven Wrapper verification command |
| `linkforge.execution.allowed-extensions` | `LINKFORGE_EXECUTION_ALLOWED_EXTENSIONS` | `java,xml,properties,json,md` | Strict comma-delimited allowlist of editable file extensions |

---

## Workflow API Examples

### 1. Create Greenfield Workflow & Human Plan Approval Gate

```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "Build a greenfield URL shortener service with Base62 encoding and click tracking"
}
```

**Response (201 Created - Paused for Plan Approval)**:
```json
{
  "id": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "requirement": "Build a greenfield URL shortener service with Base62 encoding and click tracking",
  "originalRequirement": "Build a greenfield URL shortener service with Base62 encoding and click tracking",
  "scenario": "GREENFIELD",
  "status": "WAITING_FOR_APPROVAL",
  "currentStage": "PLAN_APPROVAL",
  "planHash": "3f8b1c4a...",
  "acceptanceCriteria": [
    "AC-1: Given a valid HTTP or HTTPS destination URL, when a short link is requested, then a unique short token is generated",
    "AC-2: Given an existing short token or custom alias, when a redirect is requested, then the service issues an HTTP 302 redirect",
    "AC-3: Given link click events, when analytics are queried, then total click count and recent access events are returned"
  ],
  "tasks": [
    {
      "taskId": "TASK-1",
      "title": "Core Domain Models & Thread-Safe Store",
      "dependencies": [],
      "status": "PENDING",
      "specialistRole": "DATA_PERSISTENCE"
    },
    {
      "taskId": "TASK-2",
      "title": "URL Validation & Scheme Sanitization Engine",
      "dependencies": ["TASK-1"],
      "status": "PENDING",
      "specialistRole": "SECURITY_VALIDATION"
    }
  ]
}
```

**Approve the Plan (`POST /api/v1/workflows/{id}/approve`)**:
```http
POST /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/approve
Content-Type: application/json
X-Approval-Token: dev-approval-token

{
  "decision": "APPROVED",
  "planHash": "3f8b1c4a...",
  "approver": "lead-architect@example.com",
  "comments": "Plan verified for milestone 6 delivery."
}
```

**Response (200 OK - Approved and Specialist Coordination Executed)**:
```json
{
  "id": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "planHash": "3f8b1c4a...",
  "approval": {
    "approver": "lead-architect@example.com",
    "decision": "APPROVED",
    "planHash": "3f8b1c4a...",
    "comments": "Plan verified for milestone 6 delivery.",
    "reviewedAt": "2026-10-06T15:20:00Z"
  },
  "tasks": [
    {
      "taskId": "TASK-1",
      "status": "COMPLETED",
      "specialistRole": "DATA_PERSISTENCE"
    }
  ],
  "specialistInvocations": [
    {
      "taskId": "TASK-1",
      "status": "SUCCESS",
      "agentName": "data-persistence-specialist"
    }
  ]
}
```

---

### 2. Ambiguous Requirement & Operator Clarification Gate

**Submit Ambiguous Requirement**:
```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "make links faster and safer"
}
```

**Response (201 Created - Paused for Clarification)**:
```json
{
  "id": "9bf281d2-a720-4b8c-b0cf-5b1b467dbb22",
  "scenario": "AMBIGUOUS",
  "status": "WAITING_FOR_CLARIFICATION",
  "currentStage": "REQUIREMENT_ANALYSIS",
  "unansweredQuestions": [
    "Is this request intended as a new greenfield build or a modification to an existing codebase?",
    "What specific functional capabilities, endpoints, or quantitative constraints should be implemented?"
  ]
}
```

**Submit Operator Clarification (`POST /api/v1/workflows/{id}/clarifications`)**:
```http
POST /api/v1/workflows/9bf281d2-a720-4b8c-b0cf-5b1b467dbb22/clarifications
Content-Type: application/json
X-Clarification-Token: dev-clarification-token

{
  "clarification": "Build a new greenfield URL shortener with Base62 token generation and click analytics",
  "submittedBy": "operator@example.com"
}
```

**Response (200 OK - Clarified & Advanced to Plan Approval Gate)**:
```json
{
  "id": "9bf281d2-a720-4b8c-b0cf-5b1b467dbb22",
  "originalRequirement": "make links faster and safer",
  "requirement": "make links faster and safer\n\nClarification: Build a new greenfield URL shortener with Base62 token generation and click analytics",
  "scenario": "GREENFIELD",
  "status": "WAITING_FOR_APPROVAL",
  "currentStage": "PLAN_APPROVAL",
  "planHash": "a1b2c3d4...",
  "clarifications": [
    {
      "clarification": "Build a new greenfield URL shortener with Base62 token generation and click analytics",
      "submittedBy": "operator@example.com",
      "submittedAt": "2026-10-06T15:22:00Z"
    }
  ]
}
```

---

### 3. Safe Stop / Workflow Cancellation

**Request Safe Stop (`POST /api/v1/workflows/{id}/cancel` or `POST /api/v1/workflows/{id}/stop`)**:
```http
POST /api/v1/workflows/9bf281d2-a720-4b8c-b0cf-5b1b467dbb22/cancel
Content-Type: application/json
X-Auth-Token: dev-cancellation-token

{
  "reason": "Scope superseded by updated product requirements",
  "requestedBy": "ops-lead@example.com"
}
```

**Response (200 OK - Workflow Safely Cancelled)**:
```json
{
  "id": "9bf281d2-a720-4b8c-b0cf-5b1b467dbb22",
  "requirement": "make links faster and safer",
  "scenario": "AMBIGUOUS",
  "status": "CANCELLED",
  "currentStage": "REQUIREMENT_ANALYSIS",
  "cancellation": {
    "cancelledBy": "ops-lead@example.com",
    "reason": "Scope superseded by updated product requirements",
    "cancelledAt": "2026-10-06T15:30:00Z"
  }
}
```

---

### 4. Workflow Evidence, Audit History & Observability APIs

#### A. Workflow Summary (`GET /api/v1/workflows/{id}/summary`)
Returns high-level status, revision count, scenario classification, event/task counts, and evidence completeness.

```http
GET /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/summary
Authorization: Bearer dev-operator-token
```

**Response (200 OK)**:
```json
{
  "workflowId": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "requirementRevision": 1,
  "scenario": "GREENFIELD",
  "totalTasks": 2,
  "completedTasks": 2,
  "totalSpecialistDecisions": 2,
  "totalAuditEvents": 6,
  "clarificationCount": 0,
  "planApproved": true,
  "cancelled": false,
  "evidenceCompleteness": {
    "totalCriteria": 3,
    "addressedCriteriaCount": 3,
    "plannedCriteriaCount": 3,
    "coveragePercentage": 100.0,
    "hasCodebaseEvidence": false,
    "sourceCodeGenerationSupported": false,
    "buildExecutionSupported": false,
    "automatedTestExecutionVerified": false,
    "deploymentSupported": false,
    "unverifiedCapabilities": [
      "source-code-generation",
      "build-execution",
      "automated-test-execution",
      "deployment-and-release"
    ]
  },
  "createdAt": "2026-10-06T15:15:00Z",
  "updatedAt": "2026-10-06T15:20:00Z"
}
```

#### B. Audit History & Event Timeline (`GET /api/v1/workflows/{id}/history` or `GET .../events`)
Returns chronological, database-backed state transitions, agent decisions, gate approvals, and retry attempts.

```http
GET /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/history
X-Operator-Token: dev-operator-token
```

**Response (200 OK)**:
```json
{
  "workflowId": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "totalEvents": 4,
  "events": [
    {
      "timestamp": "2026-10-06T15:15:00Z",
      "stage": "REQUIREMENT_ANALYSIS",
      "eventType": "REQUIREMENT_ANALYSIS_COMPLETED",
      "details": "Requirement analyzed with 3 acceptance criteria",
      "metadata": {
        "scenario": "GREENFIELD",
        "criteriaCount": "3"
      }
    },
    {
      "timestamp": "2026-10-06T15:15:02Z",
      "stage": "PLAN_APPROVAL",
      "eventType": "PLAN_APPROVAL_PAUSED",
      "details": "Workflow paused awaiting human plan approval",
      "metadata": {
        "planHash": "3f8b1c4a...",
        "taskCount": "2"
      }
    },
    {
      "timestamp": "2026-10-06T15:20:00Z",
      "stage": "PLAN_APPROVAL",
      "eventType": "PLAN_APPROVED",
      "details": "Plan approved by lead-architect@example.com",
      "metadata": {
        "approver": "lead-architect@example.com"
      }
    },
    {
      "timestamp": "2026-10-06T15:20:05Z",
      "stage": "FINISHED",
      "eventType": "WORKFLOW_COMPLETED",
      "details": "Specialist coordination finished with 2 tasks",
      "metadata": {
        "completedTasks": "2"
      }
    }
  ],
  "retrievedAt": "2026-10-06T15:25:00Z"
}
```

#### C. Evidence & Acceptance-Criteria Traceability (`GET /api/v1/workflows/{id}/evidence` or `GET .../traceability`)
Maps each acceptance criterion to planned tasks, specialist agent findings, and verification bounds. Local filesystem paths are completely omitted, and event-level linkage is clearly indicated when not established.

```http
GET /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/evidence
X-Operator-Token: dev-operator-token
```

**Response (200 OK)**:
```json
{
  "workflowId": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "scenario": "GREENFIELD",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "planHash": "3f8b1c4a...",
  "planApproved": true,
  "codebaseEvidenceAvailable": false,
  "criteriaEvidence": [
    {
      "criterionId": "AC-1",
      "criterionText": "Given a valid HTTP or HTTPS destination URL, when a short link is requested, then a unique short token is generated",
      "status": "ANALYZED",
      "plannedTaskIds": ["TASK-1"],
      "specialistRoles": ["DATA_PERSISTENCE"],
      "specialistFindings": [
        {
          "taskId": "TASK-1",
          "agentName": "data-persistence-specialist",
          "specialistRole": "DATA_PERSISTENCE",
          "executionStatus": "SUCCESS",
          "provider": "ollama",
          "model": "llama3.2",
          "fallbackOccurred": false,
          "fallbackReason": null,
          "recommendations": ["Use concurrent hash map or SQL unique index on alias column"],
          "testIdeas": ["Test collision resistance under concurrent token insertions"]
        }
      ],
      "relevantEventTypes": [],
      "eventLinkageStatus": "EVENT_LEVEL_LINKAGE_UNAVAILABLE",
      "hasPersistedEvidence": true
    }
  ],
  "taskTraceability": [
    {
      "taskId": "TASK-1",
      "title": "Core Domain Models & Thread-Safe Store",
      "taskStatus": "COMPLETED",
      "specialistRole": "DATA_PERSISTENCE",
      "dependencies": [],
      "addressedCriteria": ["AC-1"],
      "specialistExecuted": true,
      "specialistAgentName": "data-persistence-specialist",
      "specialistExecutionStatus": "SUCCESS"
    }
  ],
  "verificationStatus": {
    "requirementAnalysis": "COMPLETED",
    "scenarioClassification": "GREENFIELD",
    "codebaseInspection": "NOT_APPLICABLE (GREENFIELD)",
    "taskPlanning": "COMPLETED",
    "humanApprovalGate": "APPROVED",
    "specialistAnalysis": "COMPLETED",
    "sourceCodeGeneration": "NOT_SUPPORTED",
    "buildExecution": "NOT_SUPPORTED",
    "automatedTestExecution": "UNVERIFIED",
    "deploymentAndRelease": "NOT_SUPPORTED"
  },
  "eventLinkageStatus": "EVENT_LEVEL_LINKAGE_UNAVAILABLE",
  "generatedAt": "2026-10-06T15:25:00Z"
}
```

> **Honest Capability Accounting**: For workflows that only complete planning and specialist analysis without execution (plan-only), `sourceCodeGeneration` and `buildExecution` are reported as `NOT_SUPPORTED`, and `automatedTestExecution` is reported as `UNVERIFIED`. Once a workflow is approved and executed through Milestone 10 governed execution, these are upgraded to `VERIFIED (ISOLATED_PROPOSAL)`, `VERIFIED (MAVEN_WRAPPER_BUILD)`, and `VERIFIED (TARGETED_TEST_EXECUTION)`. Non-success outcomes (e.g. `BLOCKED`, `TIMED_OUT`, `FAILED`, `ROLLED_BACK`) remain clearly distinct. Open-ended arbitrary repository editing, general-purpose arbitrary code generation, full regression test execution, and production deployment (`NOT_SUPPORTED`) remain deliberately out of scope.

---

### 5. Governed Implementation & Build Validation (Milestone 10)

This end-to-end governed workflow safely takes a supported URL-shortener change through specialist proposal synthesis, strict human approval, isolated temporary workspace patching, and Maven Wrapper build verification.

#### Governed Execution Architecture & Safety Boundaries

- **Restricted Execution Scope**: Governed execution is strictly limited to LinkForge-controlled, allowlisted project fixtures copied into a temporary workspace directory (`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`). To protect the host environment against untrusted wrapper scripts (`mvnw`/`mvnw.cmd`) and hostile Maven build plugins, caller-supplied repository paths (`repositoryPath`) are strictly rejected for execution with a persisted `BLOCKED` status, a `MUTATION_BLOCKED` audit event, and a `SUBMITTED_REPOSITORY_REJECTED` record. Brownfield read-only inspection and specialist planning remain fully supported.
- **Process Isolation & Build Timeout**: Execution runs in a dedicated temporary workspace directory (not an OS sandbox or container). The child Maven process is governed by an enforced timeout (default: 60s). Process output is drained concurrently using a bounded collector reading in chunks, safely truncating long output and avoiding memory exhaustion even on a single massive line. If the timeout expires, the child process and all its descendants are forcibly terminated, output collection is stopped safely, and a `TIMED_OUT` execution record is persisted.
- **Deterministic Alias Validation Vertical Slice**: Governed implementation currently supports deterministic custom alias validation enforcing 3–30 alphanumeric, hyphen, and underscore characters. The proposal safely modifies the core `AliasValidator` in `src/main/java/com/linkforge/service/link/` and creates executable unit tests in `src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java`. The build validator runs these tests directly (`./mvnw --batch-mode test -Dtest=CustomAliasValidationTest`), not merely compiling them.
- **Proposal Consistency & Hash Policy**:
  - `CREATE` operations must not overwrite existing files.
  - `MODIFY` and `DELETE` operations require an existing target file and a non-blank `expectedInputHash` matching the current file content.
  - Model responses are strictly validated against operation and path policies prior to approval.
  - The reviewed `planHash` cryptographically covers all planned tasks and exact file changes; the execution request must supply an exact matching `planHash`.
- **Honest System Boundaries**: LinkForge does not claim generic source code generation, arbitrary repository execution, OS-level sandboxing, or production deployment. Production deployment remains `NOT_SUPPORTED`.

#### Step 1: Create Workflow (`POST /api/v1/workflows`)
```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "Add custom alias validation enforcing alphanumeric characters between 3 and 30 characters"
}
```
**Response (201 Created)**: Returns the workflow in `WAITING_FOR_APPROVAL` or `INITIALIZED`.

#### Step 2: Propose Implementation (`POST /api/v1/workflows/{id}/propose`)
Specialist agents inspect the requirement, classify it into a supported scope (`ALIAS_VALIDATION`), and synthesize structured file change proposals with task and acceptance criterion lineage and expected input hashes.

```http
POST /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/propose
```

**Response (200 OK - Status PROPOSED)**:
```json
{
  "id": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "status": "PROPOSED",
  "currentStage": "IMPLEMENTATION_PROPOSAL",
  "planHash": "a8f3b92c4d5e6f...",
  "implementationProposal": {
    "proposalId": "prop-e605d3e0-910a-4c28-98e1-0c58a69e3d09",
    "scope": "ALIAS_VALIDATION",
    "supported": true,
    "proposalHash": "a8f3b92c4d5e6f...",
    "changes": [
      {
        "relativePath": "src/main/java/com/linkforge/service/link/AliasValidator.java",
        "operation": "MODIFY",
        "content": "package com.linkforge.service.link; ...",
        "targetHash": "5ee2ab296dd1ad65ab43f4104fe9e67b37a36a694a4933243828ad4df2ed2853",
        "taskLineage": "TASK-1",
        "criterionLineage": "AC-1",
        "specialistRole": "SECURITY_VALIDATION",
        "description": "Align custom alias validation to enforce 3 to 30 character length bounds."
      },
      {
        "relativePath": "src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java",
        "operation": "CREATE",
        "content": "package com.linkforge.service.link; ...",
        "targetHash": null,
        "taskLineage": "TASK-1",
        "criterionLineage": "AC-1",
        "specialistRole": "TESTING_QUALITY",
        "description": "Added comprehensive executable validation tests covering minimum, maximum, invalid chars, null/blank, and out-of-range aliases."
      }
    ]
  }
}
```
*(You can also retrieve the current proposal at any time with `GET /api/v1/workflows/{id}/proposal`)*

#### Step 3: Human Approval Gate (`POST /api/v1/workflows/{id}/approve`)
The human reviewer inspects the planned tasks and proposed code modifications. The reviewer submits an approval decision with the exact cryptographic `planHash` that covers both the task plan and proposal. Mismatched or missing hashes are strictly rejected (HTTP 400).

```http
POST /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/approve
Content-Type: application/json
X-Approval-Token: dev-approval-token

{
  "decision": "APPROVED",
  "planHash": "a8f3b92c4d5e6f...",
  "approver": "lead-engineer@example.com",
  "comments": "Alias validation implementation proposal approved for governed execution."
}
```

**Response (200 OK - Status APPROVED)**:
```json
{
  "id": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "status": "APPROVED",
  "currentStage": "PLAN_APPROVAL",
  "planHash": "a8f3b92c4d5e6f..."
}
```

#### Step 4: Execute Governed Implementation (`POST /api/v1/workflows/{id}/execute`)
Dispatches the approved proposal into an isolated temporary workspace. Applies containment, extension checks, operation bounds, atomic swap writes, and verifies the change using the Maven Wrapper child process.

> **Safety Notice**: Caller-supplied execution parameters, arbitrary patches, or claimed evidence are strictly rejected server-side (`@JsonIgnoreProperties(ignoreUnknown = false)`). Only the validated `planHash` is accepted. Workflows with submitted repository paths are rejected for execution.

```http
POST /api/v1/workflows/e605d3e0-910a-4c28-98e1-0c58a69e3d09/execute
Content-Type: application/json

{
  "planHash": "a8f3b92c4d5e6f..."
}
```

**Response (200 OK - Status COMPLETED)**:
```json
{
  "id": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "executionRecord": {
    "executionId": "exec-d4a1b2c3...",
    "workflowId": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
    "status": "COMPLETED",
    "stage": "FINISHED",
    "planHash": "a8f3b92c4d5e6f...",
    "appliedChanges": [
      {
        "relativePath": "src/main/java/com/linkforge/service/link/AliasValidator.java",
        "operation": "MODIFY",
        "newHash": "5ee2ab296dd1ad65ab43f4104fe9e67b37a36a694a4933243828ad4df2ed2853",
        "appliedAt": "2026-10-06T18:00:01Z"
      },
      {
        "relativePath": "src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java",
        "operation": "CREATE",
        "newHash": "8a7c2b3d...",
        "appliedAt": "2026-10-06T18:00:01Z"
      }
    ],
    "buildValidation": {
      "command": "./mvnw --batch-mode test -Dtest=CustomAliasValidationTest",
      "exitCode": 0,
      "durationMs": 3420,
      "status": "SUCCESS",
      "output": "[INFO] Scanning for projects...\n[INFO] Running com.linkforge.service.link.CustomAliasValidationTest\n[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n..."
    },
    "rollback": null,
    "completedAt": "2026-10-06T18:00:05Z"
  }
}
```
*(Query the complete execution record at any time with `GET /api/v1/workflows/{id}/execution`)*

#### Step 5: Verified Capability Accounting in Evidence (`GET /api/v1/workflows/{id}/evidence`)
After successful governed execution, evidence reports:
```json
{
  "verificationStatus": {
    "requirementAnalysis": "COMPLETED",
    "scenarioClassification": "GREENFIELD",
    "taskPlanning": "COMPLETED",
    "humanApprovalGate": "APPROVED",
    "specialistAnalysis": "COMPLETED",
    "sourceCodeGeneration": "VERIFIED (ISOLATED_PROPOSAL)",
    "buildExecution": "VERIFIED (MAVEN_WRAPPER_BUILD)",
    "automatedTestExecution": "VERIFIED (TARGETED_TEST_EXECUTION)",
    "deploymentAndRelease": "NOT_SUPPORTED"
  }
}
```

##### Capability Accounting Distinctions Across Workflow Lifecycle:
- **Plan-Only Workflows** (planning and specialist coordination completed without execution):
  `sourceCodeGeneration`: `NOT_SUPPORTED`, `buildExecution`: `NOT_SUPPORTED`, `automatedTestExecution`: `UNVERIFIED`
- **Successfully Executed Governed Workflows** (governed proposal applied and `./mvnw --batch-mode test -Dtest=CustomAliasValidationTest` succeeded):
  `sourceCodeGeneration`: `VERIFIED (ISOLATED_PROPOSAL)`, `buildExecution`: `VERIFIED (MAVEN_WRAPPER_BUILD)`, `automatedTestExecution`: `VERIFIED (TARGETED_TEST_EXECUTION)`
- **Blocked Workflows** (e.g. execution rejected for caller-supplied repository paths):
  `sourceCodeGeneration`: `BLOCKED`, `buildExecution`: `BLOCKED`, `automatedTestExecution`: `BLOCKED`
- **Timed-Out Workflows** (child build process exceeded 60s timeout):
  `sourceCodeGeneration`: `FAILED (TIMED_OUT)`, `buildExecution`: `TIMED_OUT`, `automatedTestExecution`: `FAILED (TIMED_OUT)`
- **Rolled-Back Workflows** (build validation failed and changes were verified rolled back):
  `sourceCodeGeneration`: `ROLLED_BACK (VERIFIED_RESTORATION)`, `buildExecution`: `FAILED (ROLLED_BACK)`, `automatedTestExecution`: `FAILED (ROLLED_BACK)`
- **Failed Workflows** (unrecoverable execution errors):
  `sourceCodeGeneration`: `FAILED`, `buildExecution`: `FAILED`, `automatedTestExecution`: `FAILED`

---

## URL Shortener API

- `POST /api/v1/links`: Shorten a valid HTTP/HTTPS URL with optional custom alias (`201 Created`).
- `GET /r/{tokenOrAlias}`: Fast HTTP 302 redirection to original target URL.
- `GET /api/v1/links/{tokenOrAlias}/analytics`: View click counts, timestamps, and recent click events.

---

## Local Verification & Testing

### Repeatable End-to-End Scenarios (Milestone 9)

Run the automated scenario test suite or executable script to verify all end-to-end agentic workflows through public APIs:

```bash
# Run Milestone 9 repeatable scenario integration tests (includes all 6 scenarios)
./mvnw test -Dtest=RepeatableWorkflowScenariosIntegrationTest

# Or run via the automated scenario runner script
./scripts/run-scenarios.sh
```

#### Offline vs. Live Ollama Execution
- **Offline / Deterministic (Default)**: All scenarios run 100% offline without requiring Ollama or external models. Scenarios 1–5 utilize LinkForge's deterministic specialists and scenario classifiers, guaranteeing fast, repeatable, deterministic passes in any CI/CD environment.
- **Optional Live Ollama Execution**: Scenario 6 detects whether Ollama is active locally at `http://localhost:11434` with `llama3.2`.
  - When Ollama is available, it dynamically dispatches live prompts and verifies real model-backed analysis through Spring AI.
  - When Ollama is offline or unavailable, Scenario 6 gracefully skips (`Assumptions.assumeTrue(...)`), ensuring builds remain green without hard external dependencies.

#### Expected Observable Outcomes
| Scenario | Public API Action | Expected Outcome | Observable Gate / State |
| :--- | :--- | :--- | :--- |
| **1. Greenfield Shortener** | `POST /api/v1/workflows` &rarr; `POST .../approve` | Analyzed, planned into 5 tasks, approved, and coordinated | Status `COMPLETED`, Stage `FINISHED`, 5 specialist invocations, full audit history |
| **2. Ambiguous Clarification** | `POST /api/v1/workflows` &rarr; `POST .../clarifications` | Ambiguity detected with questions, clarified, revision incremented | Status `WAITING_FOR_CLARIFICATION` &rarr; revision 2 &rarr; `WAITING_FOR_APPROVAL` &rarr; `COMPLETED` |
| **3. Brownfield Inspection** | `POST /api/v1/workflows` (with `repositoryPath`) | Safe read-only inspection extracts file stats & languages from disposable fixture | `codebaseEvidenceAvailable=true`, local host paths omitted, status `COMPLETED` |
| **4. Human Approval Gate** | `POST /api/v1/workflows` &rarr; `POST .../approve` (rejection) | Blocked in `WAITING_FOR_APPROVAL`, tampered hash rejected (400), rejected plan permanently locked | Status `REJECTED`, Stage `PLAN_APPROVAL`, subsequent actions return 409 Conflict |
| **5a. Transient Retry Recovery** | `POST /api/v1/workflows` &rarr; `POST .../approve` | Transient stage failure automatically retried with backoff, recovered on attempt 2, and coordinated to completion | Status `WAITING_FOR_APPROVAL` &rarr; `COMPLETED`, audit events `STAGE_TRANSIENT_FAILURE`, `STAGE_RETRY_SCHEDULED`, `STAGE_RETRY_SUCCEEDED` |
| **5b. Safe Stop / Cancellation** | `POST /api/v1/workflows` &rarr; `POST .../cancel` | Immediate interruption, stop record captured, subsequent actions blocked | Status `CANCELLED`, `cancellation` recorded, audit event `WORKFLOW_CANCELLED` |
| **6. Live Ollama Run (Optional)** | `POST /api/v1/workflows` &rarr; `POST .../approve` | Live model invocation via Ollama when active; gracefully skipped when offline | Model-backed decisions recorded, status `COMPLETED` |

---

### Complete Project Verification

Execute the complete automated test suite across all milestones:

```bash
# Run all verification tests across all milestones (1-10)
./mvnw clean verify

# Run Milestone 10 governed execution safety and boundary unit tests
./mvnw test -Dtest=GovernedExecutionSafetyTest

# Run Milestone 10 governed execution end-to-end integration tests
./mvnw test -Dtest=GovernedExecutionIntegrationTest

# Run Milestone 9 repeatable scenario integration tests
./mvnw test -Dtest=RepeatableWorkflowScenariosIntegrationTest

# Run Milestone 8 workflow evidence and observability service tests
./mvnw test -Dtest=WorkflowEvidenceAndObservabilityServiceTest

# Run Milestone 8 operator observability API integration tests
./mvnw test -Dtest=WorkflowEvidenceAndObservabilityIntegrationTest

# Run Milestone 7 bounded retry and cancellation service tests
./mvnw test -Dtest=WorkflowRetryAndCancellationServiceTest

# Run Milestone 7 safe stop and cancellation API integration tests
./mvnw test -Dtest=WorkflowRetryAndCancellationIntegrationTest
```

---

### Milestone 10 Governed Implementation Local Demo Walkthrough

To run a complete end-to-end local demo of the governed implementation workflow:

1. **Start the application**:
   ```bash
   ./mvnw spring-boot:run
   ```

2. **Submit a URL-shortener requirement with supported scope (e.g., alias validation)**:
   ```bash
   WORKFLOW_ID=$(curl -s -X POST http://localhost:8080/api/v1/workflows \
     -H "Content-Type: application/json" \
     -d '{"requirement": "Add custom alias validation enforcing alphanumeric characters between 3 and 30 characters"}' \
     | jq -r '.id')
   echo "Workflow created: ${WORKFLOW_ID}"
   ```

3. **Synthesize the structured implementation proposal**:
   ```bash
   PROPOSAL_RES=$(curl -s -X POST "http://localhost:8080/api/v1/workflows/${WORKFLOW_ID}/propose")
   PLAN_HASH=$(echo "${PROPOSAL_RES}" | jq -r '.planHash')
   echo "Synthesized proposal with combined planHash: ${PLAN_HASH}"
   ```

4. **Review and approve the implementation plan**:
   ```bash
   curl -s -X POST "http://localhost:8080/api/v1/workflows/${WORKFLOW_ID}/approve" \
     -H "Content-Type: application/json" \
     -H "X-Approval-Token: dev-approval-token" \
     -d "{\"decision\": \"APPROVED\", \"planHash\": \"${PLAN_HASH}\", \"approver\": \"operator@example.com\", \"comments\": \"Approved for governed execution.\"}" \
     | jq '{id: .id, status: .status, planHash: .planHash}'
   ```

5. **Execute the governed implementation in an isolated workspace**:
   ```bash
   curl -s -X POST "http://localhost:8080/api/v1/workflows/${WORKFLOW_ID}/execute" \
     -H "Content-Type: application/json" \
     -d "{\"planHash\": \"${PLAN_HASH}\"}" \
     | jq '{id: .id, status: .status, executionRecord: {status: .executionRecord.status, buildStatus: .executionRecord.buildValidation.status, durationMs: .executionRecord.buildValidation.durationMs}}'
   ```

6. **Inspect the audit evidence and execution record**:
   ```bash
   curl -s "http://localhost:8080/api/v1/workflows/${WORKFLOW_ID}/evidence" \
     -H "X-Operator-Token: dev-operator-token" \
     | jq '.verificationStatus'
   ```
