#!/usr/bin/env bash
# ==============================================================================
# LinkForge - Milestone 9 Repeatable Scenario Test Runner
# Exercises end-to-end scenarios through public APIs using isolated fixtures.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

echo "========================================================================"
echo " LinkForge - Repeatable Agentic Workflow Scenarios (Milestone 9)"
echo "========================================================================"

# Determine JAVA_HOME if not already set
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ "$(uname)" == "Darwin" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home)"
    fi
fi

# Check Ollama status
OLLAMA_STATUS="offline"
if curl -s --connect-timeout 1 http://localhost:11434/api/tags >/dev/null 2>&1; then
    OLLAMA_STATUS="online (localhost:11434)"
fi

echo "Environment:"
echo "  Project root : ${PROJECT_ROOT}"
echo "  Java version : $(java -version 2>&1 | head -n 1)"
echo "  Ollama status: ${OLLAMA_STATUS}"
echo ""
echo "Executing repeatable end-to-end scenarios:"
echo "  1. Greenfield URL-shortener: analysis, classification, specialist planning & approval"
echo "  2. Ambiguous requirement: clarification gate, revision bump & resume"
echo "  3. Brownfield repository: safe read-only inspection & backward-compatible planning"
echo "  4. Human governance approval gate: tamper rejection & state locking"
echo "  5. Transient recovery & safe stop: deterministic stage failure retry recovery (5a) & idempotent cancellation (5b)"
echo "  6. Optional live Ollama execution (runs live if Ollama is available, skips gracefully if offline)"
echo "------------------------------------------------------------------------"

./mvnw test -Dtest=RepeatableWorkflowScenariosIntegrationTest

echo "------------------------------------------------------------------------"
echo "All repeatable scenarios executed successfully!"
echo "========================================================================"
