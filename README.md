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

## Configuration Reference

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

### 1. Create Greenfield Workflow with Specialist Coordination
```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "Build a greenfield URL shortener service with Base62 encoding and click tracking"
}
```
**Response (201 Created)**:
```json
{
  "id": "e605d3e0-910a-4c28-98e1-0c58a69e3d09",
  "requirement": "Build a greenfield URL shortener service with Base62 encoding and click tracking",
  "scenario": "GREENFIELD",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
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
      "status": "COMPLETED",
      "specialistRole": "DATA_PERSISTENCE"
    },
    {
      "taskId": "TASK-2",
      "title": "URL Validation & Scheme Sanitization Engine",
      "dependencies": ["TASK-1"],
      "status": "COMPLETED",
      "specialistRole": "SECURITY_VALIDATION"
    },
    {
      "taskId": "TASK-3",
      "title": "Collision-Free Token Generator",
      "dependencies": ["TASK-1"],
      "status": "COMPLETED",
      "specialistRole": "API_BEHAVIOR"
    },
    {
      "taskId": "TASK-4",
      "title": "Redirection Controller & Atomic Analytics",
      "dependencies": ["TASK-2", "TASK-3"],
      "status": "COMPLETED",
      "specialistRole": "API_BEHAVIOR"
    },
    {
      "taskId": "TASK-5",
      "title": "Automated Verification Suite",
      "dependencies": ["TASK-4"],
      "status": "COMPLETED",
      "specialistRole": "TESTING_QUALITY"
    }
  ],
  "specialistInvocations": [
    {
      "invocationId": "3b29c92a-...",
      "taskId": "TASK-1",
      "agentName": "data-persistence-specialist",
      "role": "DATA_PERSISTENCE",
      "status": "SUCCESS",
      "inputSummary": "Task TASK-1: Core Domain Models & Thread-Safe Store",
      "outputSummary": "Data Persistence analysis for TASK-1: Structured relational constraints and thread-safe persistence guarantees.",
      "recommendations": [
        "Establish embedded H2 relational schema with dedicated tables: 'links' and 'click_events'.",
        "Apply unique database constraints on token and custom_alias.",
        "Use Spring JdbcTemplate with atomic update queries to prevent race conditions."
      ],
      "testIdeas": [
        "Verify database constraint enforcement rejecting duplicate token or alias insertion.",
        "Test concurrent redirect clicks asserting accurate click count tally in H2 database."
      ],
      "addressedCriteria": ["AC-1", "AC-2"],
      "provider": "deterministic",
      "model": "rules",
      "fallbackOccurred": false,
      "startedAt": "2026-10-06T18:50:00Z",
      "completedAt": "2026-10-06T18:50:00Z"
    }
  ]
}
```

### 2. Create Brownfield Workflow with Codebase Inspection
```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "Refactor the existing repository to enhance link storage and database queries",
  "repositoryPath": "./"
}
```

### 3. Handle Ambiguous Requirement & Submit Clarification
```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "make links faster and safer"
}
```
**Response (201 Created)**:
```json
{
  "id": "9bf281d2-...",
  "scenario": "AMBIGUOUS",
  "status": "WAITING_FOR_CLARIFICATION",
  "currentStage": "SCENARIO_CLASSIFICATION",
  "unansweredQuestions": [
    "Is this request intended as a new greenfield build or a modification to an existing codebase?",
    "What specific functional capabilities, endpoints, or quantitative constraints should be implemented?"
  ]
}
```

**Submit Clarification**:
```http
POST /api/v1/workflows/9bf281d2-.../clarifications
Content-Type: application/json

{
  "clarification": "Build a new greenfield REST service with p99 redirect under 10ms and HTTPS validation"
}
```

---

## URL Shortener API

- `POST /api/v1/links`: Shorten a valid HTTP/HTTPS URL with optional custom alias (`201 Created`).
- `GET /r/{tokenOrAlias}`: Fast HTTP 302 redirection to original target URL.
- `GET /api/v1/links/{tokenOrAlias}/analytics`: View click counts, timestamps, and recent click events.
