# Local AI Coding Agent

This folder starts a fully local AI coding agent stack with Docker only:

- Ollama as the local LLM server
- Qwen2.5-Coder 14B as the default model
- OpenHands as the autonomous coding agent UI

The first setup downloads Docker images and the Ollama model. After that, the
agent talks to the local Ollama container and does not need external AI APIs.

## Requirements

- Docker Desktop
- Docker Desktop must allow access to the Docker socket.
  On macOS this is in Docker Desktop settings.
- Around 36 GB RAM is enough to try `qwen2.5-coder:14b`, but it can be slow in
  Docker on macOS because the Ollama Linux container does not use Apple Metal
  acceleration like native Ollama would.

No host Python, Node, Java, Ollama or OpenHands install is required for this
local AI stack.

## Start For The First Time

Run from the repository root:

```bash
LOCAL_WORKSPACE="$(pwd)" docker compose -f docker/local-ai/docker-compose.yml up -d
```

This starts:

- `software-factory-ollama`
- `software-factory-ollama-model`, a one-shot container that pulls the model
- `software-factory-openhands`

The first run can take a long time because `qwen2.5-coder:14b` is about 9 GB.

Follow the model download:

```bash
docker compose -f docker/local-ai/docker-compose.yml logs -f ollama-model
```

Open OpenHands:

```text
http://localhost:3000
```

## OpenHands Settings

The compose file already passes these values to OpenHands:

```text
Custom Model: openai/qwen2.5-coder:14b
Base URL:     http://ollama:11434/v1
API Key:      local-llm
```

If OpenHands still asks for LLM settings in the UI, use those same values and
save them.

In the OpenHands prompt, tell it to work in:

```text
/workspace/softwarefactory
```

That path is the mounted repository from `LOCAL_WORKSPACE`.

Example prompt:

```text
Work in /workspace/softwarefactory.
Run the relevant tests.
Fix the code and iterate until the tests pass.
Do not use external APIs or internet access.
```

## Use Another Repository

Point `LOCAL_WORKSPACE` at another absolute path:

```bash
LOCAL_WORKSPACE="/path/to/repo" docker compose -f docker/local-ai/docker-compose.yml up -d
```

OpenHands will see it as:

```text
/workspace/softwarefactory
```

## Use A Smaller Model

If 14B is too slow or Docker Desktop runs out of memory, switch to 7B:

```bash
LOCAL_WORKSPACE="$(pwd)" OLLAMA_MODEL=qwen2.5-coder:7b docker compose -f docker/local-ai/docker-compose.yml up -d
```

Or pull only the model:

```bash
OLLAMA_MODEL=qwen2.5-coder:7b docker compose -f docker/local-ai/docker-compose.yml up ollama-model
```

Then restart OpenHands:

```bash
docker compose -f docker/local-ai/docker-compose.yml up -d openhands
```

## Stop

Stop the containers:

```bash
docker compose -f docker/local-ai/docker-compose.yml stop
```

Remove the containers but keep downloaded models and OpenHands state:

```bash
docker compose -f docker/local-ai/docker-compose.yml down
```

Remove everything, including the Ollama model cache and OpenHands state:

```bash
docker compose -f docker/local-ai/docker-compose.yml down -v
```

## Check The Local LLM

From the host:

```bash
curl http://localhost:11434/api/tags
```

From Docker:

```bash
docker exec -it software-factory-ollama ollama list
```

Run a quick prompt:

```bash
docker exec -it software-factory-ollama ollama run qwen2.5-coder:14b "Write a Kotlin hello world"
```

## Notes

- OpenHands creates sandbox containers through the Docker socket. This is why
  `/var/run/docker.sock` is mounted.
- The repository is mounted into OpenHands sandboxes using `SANDBOX_VOLUMES`.
- The stack does not configure Tavily, OpenAI, Anthropic, Google or GitHub API
  keys.
- OpenHands local-model behavior depends heavily on the model. If it behaves
  like a chatbot instead of editing files, try smaller tasks first or switch
  models.
