#!/usr/bin/env bash
#
# Start de OpenHands CLI interactief in een container, met je code gemount
# op /workspace en gekoppeld aan de lokale Ollama/Qwen-container.
#
# Vereist: de local-ai stack draait (ollama + qwen):
#   LOCAL_WORKSPACE="$(pwd)" docker compose -f docker/local-ai/docker-compose.yml up -d
# En de image is gebouwd:
#   docker build -f docker/local-ai/Dockerfile.openhands-cli -t openhands-cli:local docker/local-ai
#
# Gebruik:
#   ./docker/local-ai/run-openhands-cli.sh [PAD_NAAR_REPO]
#
# Zonder argument wordt de huidige directory gemount. In de TUI kun je dan
# bijvoorbeeld vragen: "What does the code in this repository do?"
#
# Env-overrides:
#   OLLAMA_MODEL   (default: qwen2.5-coder:14b)
#   OH_NETWORK     (default: software-factory-local-ai_default)
#   OH_IMAGE       (default: openhands-cli:local)

set -euo pipefail

WORKSPACE="${1:-$PWD}"
WORKSPACE="$(cd "$WORKSPACE" && pwd)"
MODEL="${OLLAMA_MODEL:-qwen2.5-coder:14b}"
NETWORK="${OH_NETWORK:-software-factory-local-ai_default}"
IMAGE="${OH_IMAGE:-openhands-cli:local}"

echo "OpenHands CLI"
echo "  workspace : ${WORKSPACE}  ->  /workspace"
echo "  model     : ollama/${MODEL}  (via http://ollama:11434 op netwerk ${NETWORK})"
echo

# --always-approve: agent voert tools autonoom uit (geen approval-prompts).
#   Verwijder die vlag als je elke actie handmatig wilt goedkeuren.
exec docker run -it --rm \
  --network "${NETWORK}" \
  -e LLM_MODEL="ollama/${MODEL}" \
  -e LLM_BASE_URL="http://ollama:11434" \
  -e LLM_API_KEY="local-llm" \
  -e OPENHANDS_SUPPRESS_BANNER=1 \
  -v "${WORKSPACE}:/workspace" \
  "${IMAGE}" \
  openhands --override-with-envs --always-approve
