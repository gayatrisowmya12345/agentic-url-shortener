# LinkForge

A governed multi-agent software engineering workbench demonstrated through an enterprise-grade URL shortener.

LinkForge combines specialized AI agents with deterministic safety guards, bounded codebase inspection, and transactional data persistence.

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
   - Requires a valid `repositoryPath`.
   - Performs safe, read-only codebase inspection and grounds acceptance criteria and task planning strictly in verified codebase evidence.

3. **AMBIGUOUS**:
   - The requirement lacks sufficient information, verifiable bounds, or operational metrics to safely proceed.
   - Pauses the workflow in `WAITING_FOR_CLARIFICATION` with targeted questions.
   - Blocks plan generation and source code changes until clarified.

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

### Codebase Inspection (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `linkforge.inspection.approved-root` | `LINKFORGE_INSPECTION_APPROVED_ROOT` | `${user.dir}` | Base directory boundary for brownfield inspections (defaults to repository root for local development) |
| `linkforge.inspection.max-file-count` | `LINKFORGE_INSPECTION_MAX_FILE_COUNT` | `500` | Maximum allowable files within inspected repository |
| `linkforge.inspection.max-total-size-bytes`| `LINKFORGE_INSPECTION_MAX_TOTAL_SIZE_BYTES` | `10485760` (10 MB) | Maximum allowable total repository byte size |
| `linkforge.inspection.max-file-size-bytes` | `LINKFORGE_INSPECTION_MAX_FILE_SIZE_BYTES` | `524288` (512 KB) | Maximum allowable size per individual file |
| `linkforge.inspection.max-snippet-lines` | `LINKFORGE_INSPECTION_MAX_SNIPPET_LINES` | `40` | Maximum lines captured per key snippet |
| `linkforge.inspection.max-evidence-files` | `LINKFORGE_INSPECTION_MAX_EVIDENCE_FILES` | `30` | Maximum relative source paths recorded in evidence |

> [!NOTE]
> **Approved Root for Local Development**:
> By default, `linkforge.inspection.approved-root` defaults to the working directory (`${user.dir}`). Brownfield repository paths submitted via the API must be **relative** to this approved root (e.g., `.` or `./submodule` or `fixtures/legacy-repo`). Absolute paths and traversal escapes (`..`) outside this boundary are strictly rejected. To target a different workspace root, set the `LINKFORGE_INSPECTION_APPROVED_ROOT` environment variable or configure `linkforge.inspection.approved-root` in `application.properties`.

### AI Model Provider (`application.properties`)
| Property | Default | Description |
| :--- | :--- | :--- |
| `linkforge.ai.enabled` | `false` | Enable model-backed agent calls (defaults to deterministic fallback) |
| `linkforge.ai.provider` | `ollama` | Provider identifier (`ollama`, `fake`) |
| `linkforge.ai.model` | `llama3.2` | Model name |
| `linkforge.ai.base-url` | `http://localhost:11434` | Ollama service endpoint |

---

## Workflow API Examples

### 1. Create Greenfield Workflow
```http
POST /api/v1/workflows
Content-Type: application/json

{
  "requirement": "Build a high-performance URL shortener with token generation and redirection"
}
```
**Response (201 Created)**:
```json
{
  "id": "e605d3e0-...",
  "scenario": "GREENFIELD",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "acceptanceCriteria": [...],
  "tasks": [...]
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
**Response (201 Created)**:
```json
{
  "id": "7ac15b81-...",
  "scenario": "BROWNFIELD",
  "repositoryPath": "/Users/.../agentic-url-shortener",
  "repositorySummary": "Inspected repository at '...': 42 files (128450 bytes), languages: Java, frameworks: Maven, Spring Boot...",
  "status": "COMPLETED",
  "currentStage": "FINISHED"
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
**Response (200 OK)**:
```json
{
  "id": "9bf281d2-...",
  "scenario": "GREENFIELD",
  "status": "COMPLETED",
  "currentStage": "FINISHED",
  "acceptanceCriteria": [...]
}
```

---

## URL Shortener API

- `POST /api/v1/links`: Shorten a valid HTTP/HTTPS URL with optional custom alias (`201 Created`).
- `GET /r/{tokenOrAlias}`: Fast HTTP 302 redirection to original target URL.
- `GET /api/v1/links/{tokenOrAlias}/analytics`: View click counts, timestamps, and recent click events.
