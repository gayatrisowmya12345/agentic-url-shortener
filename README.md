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

---

## Workflow Lifecycle & States

### Workflow States (`WorkflowStatus`)
- `INITIALIZED`: Workflow instance created.
- `IN_PROGRESS`: Actively executing analysis or coordination.
- `WAITING_FOR_CLARIFICATION`: Paused awaiting operator clarification.
- `WAITING_FOR_APPROVAL`: Plan generated; paused awaiting human plan approval.
- `REJECTED`: Plan rejected by human approver; execution terminated.
- `COMPLETED`: Plan approved and all specialist tasks coordinated successfully.
- `FAILED`: Execution terminated due to unrecoverable error.
- `CANCELLED`: Execution safely stopped; terminal state blocking subsequent transitions or resume.

### Workflow Stages (`WorkflowStage`)
- `REQUIREMENT_ANALYSIS`: Requirement interpretation and criteria extraction.
- `SCENARIO_CLASSIFICATION`: Greenfield vs. brownfield vs. ambiguous classification.
- `CODEBASE_INSPECTION`: Safe, read-only evidence gathering for brownfield workflows.
- `TASK_PLANNING`: Dependency-aware task graph synthesis and plan hash computation.
- `PLAN_APPROVAL`: Human governance gate prior to specialist dispatch.
- `SPECIALIST_COORDINATION`: Concurrent topological execution of specialist agents.
- `FINISHED`: Terminal stage following completion or rejection.

---

## Configuration Reference

### Workflow Security & Governance Gates (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.workflow.security.enabled` | `LINKFORGE_WORKFLOW_SECURITY_ENABLED` | `false` | Enable authorization token checks for clarification, approval, and cancellation endpoints |
| `linkforge.workflow.security.clarification-token` | `LINKFORGE_WORKFLOW_CLARIFICATION_TOKEN` | `dev-clarification-token` | Shared secret token required for clarification submission |
| `linkforge.workflow.security.approval-token` | `LINKFORGE_WORKFLOW_APPROVAL_TOKEN` | `dev-approval-token` | Shared secret token required for human plan approval submission |
| `linkforge.workflow.security.cancellation-token` | `LINKFORGE_WORKFLOW_CANCELLATION_TOKEN` | `dev-cancellation-token` | Shared secret token required for workflow cancellation/stop |
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

## URL Shortener API

- `POST /api/v1/links`: Shorten a valid HTTP/HTTPS URL with optional custom alias (`201 Created`).
- `GET /r/{tokenOrAlias}`: Fast HTTP 302 redirection to original target URL.
- `GET /api/v1/links/{tokenOrAlias}/analytics`: View click counts, timestamps, and recent click events.

---

## Local Verification & Testing

Execute the complete automated test suite without external dependencies:

```bash
# Run all verification tests
./mvnw clean verify

# Run Milestone 7 bounded retry and cancellation service tests
./mvnw test -Dtest=WorkflowRetryAndCancellationServiceTest

# Run Milestone 7 safe stop and cancellation API integration tests
./mvnw test -Dtest=WorkflowRetryAndCancellationIntegrationTest
```
