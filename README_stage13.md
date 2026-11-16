# Negotiation engine, stage 13 (Neural): Automatic Background Local Language Model Runner + Proactive AI Proposals

Unzip at the repository root (`C:\dev\Unciv-master\Unciv-master`); cumulative. Then:
  gradlew.bat :core:compileKotlin
  gradlew.bat :tests:test --tests "com.unciv.logic.commitments.*"
  gradlew.bat desktop:run

## 1. Real Local Neural Language Models Auto-Managed in the Background (`BackgroundLlmRunner` in `OnDeviceLlm.kt`)
- **No template replacement:** Keeps 100% of the real neural LLM pipeline (`gemma4:e2b`, `qwen2.5:1.5b`, `gemma4:e4b`), natural language understanding, slang parsing (`SemanticNegotiationParser`), multi-asset barter (`MultiAssetBarter`), and all Stage 12 screenshot bug fixes.
- **Automatic Background Startup When Needed (`BackgroundLlmRunner.ensureRunning`):**
  - When you open `Talk` (`ChatTable`) or send a message while your local server is turned off:
    1. If a `.gguf` model file and standalone `llama-server` exist in `llm-models/`, Unciv automatically spawns `llama-server -m <model.gguf> --host 127.0.0.1 --port 11435 -c 4096 -ngl 99` silently in the background and routes chat to it.
    2. Otherwise, Unciv automatically locates your local `ollama` executable (`%LOCALAPPDATA%\Programs\Ollama\ollama.exe` on Windows, `/opt/homebrew/bin/ollama` or `/usr/local/bin/ollama` on Mac/Linux, or `PATH`), spawns `ollama serve` silently in the background, waits for it to come online, and runs your neural language model (`gemma4:e2b`).
- **Automatic Background Shutdown When Diplomacy Closes (`LlmSettings.onDiplomacyClosed`):**
  - When you click the Close (`X`) button on `DiplomacyScreen` (or exit the game), Unciv unloads the model weights from GPU/CPU memory (`keep_alive: 0`) and terminates the background process it started so it only runs when needed.
- **Self-Contained Engine + GGUF Download (`OnDeviceModelManager`):**
  - Clicking **Download On-Device** in `AI Model Setup` on a PC without Ollama automatically downloads both the standalone `llama-server` binary for your OS (`llm-models/bin/`) and the quantized `.gguf` model (`llm-models/`).

## 2. Live Proactive AI Diplomatic Proposals (`NegotiationSystem.checkProactiveAiMessages`)
- AI leaders proactively message you on their turn to propose **mutual Non-Aggression Pacts**, **joint war bounties** against shared enemies, or **war loans**, and pre-stage the confirmable Deal Card in `ChatTable`.
