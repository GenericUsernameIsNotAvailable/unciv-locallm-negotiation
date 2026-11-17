package com.unciv.logic.commitments.llm

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Specification for a quantized local neural language model sized for phones and desktops (1.0–2.7 GB).
 * Models can be downloaded on demand into the app's local `llm-models/` directory or run via a background runtime.
 */
data class MobileModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxSizeMb: Int,
    val recommendedRamGb: Int,
    val downloadUrl: String,
    val ollamaTag: String
) {
    companion object {
        val CATALOG: List<MobileModelSpec> = listOf(
            MobileModelSpec(
                id = "gemma4-e2b-q4",
                displayName = "Gemma 4 E2B Instruct (Q4_K_M, ~1.5 GB — Recommended for S25 / iPhone 16e / Desktop)",
                fileName = "gemma-4-e2b-it-Q4_K_M.gguf",
                approxSizeMb = 1520,
                recommendedRamGb = 8,
                downloadUrl = "https://huggingface.co/bartowski/google_gemma-4-E2B-it-GGUF/resolve/main/google_gemma-4-E2B-it-Q4_K_M.gguf",
                ollamaTag = "gemma4:e2b"
            ),
            MobileModelSpec(
                id = "qwen2.5-1.5b-q4",
                displayName = "Qwen 2.5 1.5B Instruct (Q4_K_M, ~1.0 GB — Ultra-light mobile / fast CPU)",
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                approxSizeMb = 990,
                recommendedRamGb = 6,
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                ollamaTag = "qwen2.5:1.5b"
            ),
            MobileModelSpec(
                id = "gemma4-e4b-q4",
                displayName = "Gemma 4 E4B Instruct (Q4_K_M, ~2.7 GB — 12 GB+ RAM devices / Desktop)",
                fileName = "gemma-4-e4b-it-Q4_K_M.gguf",
                approxSizeMb = 2750,
                recommendedRamGb = 12,
                downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
                ollamaTag = "gemma4:e4b"
            )
        )

        fun byIdOrDefault(id: String): MobileModelSpec =
            CATALOG.firstOrNull { it.id.equals(id, ignoreCase = true) || it.ollamaTag.equals(id, ignoreCase = true) }
                ?: CATALOG.first()
    }
}

/**
 * Platform-independent seam for an in-process on-device inference runtime (llama.cpp JNI / LiteRT-LM on Android,
 * llama.cpp Metal XCFramework on iOS). Because mobile devices share RAM between the OS, GPU, and the game,
 * the model is loaded when diplomacy chat opens and unloaded when diplomacy closes.
 */
interface NativeLlmBridge {
    val runtimeName: String
    val isLoaded: Boolean

    /** Loads the quantized model file at [modelPath] into native memory. */
    fun loadModel(modelPath: String, contextSize: Int = 2048, gpuLayers: Int = 99)

    /** Frees native model weights and KV-cache memory when leaving the diplomacy screen. */
    fun unloadModel()

    /** Runs synchronous generation on the loaded native model (called from a background thread). */
    fun generate(request: LlmRequest): LlmResponse
}

/**
 * Manages the on-device `llm-models/` directory inside the app's local storage, tracks which `.gguf` models
 * and standalone `llama-server` binaries are downloaded, and streams downloads with progress updates.
 */
class OnDeviceModelManager(private val modelsDirProvider: () -> File) {

    fun modelsDir(): File = modelsDirProvider().apply { if (!exists()) mkdirs() }

    fun binDir(): File = File(modelsDir(), "bin").apply { if (!exists()) mkdirs() }

    fun modelFile(spec: MobileModelSpec): File = File(modelsDir(), spec.fileName)

    fun isDownloaded(spec: MobileModelSpec): Boolean {
        val file = modelFile(spec)
        return file.exists() && file.length() > MIN_VALID_MODEL_BYTES
    }

    fun downloadedModels(): List<MobileModelSpec> =
        MobileModelSpec.CATALOG.filter { isDownloaded(it) }

    fun deleteModel(spec: MobileModelSpec): Boolean =
        modelFile(spec).takeIf { it.exists() }?.delete() ?: false

    /** Locates a standalone `llama-server` executable inside `llm-models/bin/`, `llm-models/`, or on `PATH`. */
    fun findLlamaServerBinary(): File? {
        val isWin = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")
        val exeName = if (isWin) "llama-server.exe" else "llama-server"
        val candidates = listOf(
            File(binDir(), exeName),
            File(modelsDir(), exeName)
        )
        for (c in candidates) {
            if (c.isFile && c.length() > 10_000L) return c
        }
        return BackgroundLlmRunner.findExecutableOnPath(exeName)
    }

    /**
     * Downloads [spec] into `modelsDir()` via a `.part` temporary file and renames it atomically on completion.
     * On desktop platforms, if no standalone `llama-server` binary is present in `binDir()`, also downloads and
     * extracts the standalone `llama.cpp` server binary so the `.gguf` model can run with zero external tools.
     */
    fun download(spec: MobileModelSpec, onProgress: (downloadedMb: Int, totalMb: Int) -> Unit = { _, _ -> }): File {
        try {
            if (findLlamaServerBinary() == null) {
                downloadStandaloneLlamaServerIfDesktop()
            }
        } catch (_: Exception) {
            // Non-fatal if offline or on a mobile OS where JNI is used instead
        }

        val dir = modelsDir().apply { mkdirs() }
        val target = File(dir, spec.fileName)
        val part = File(dir, "${spec.fileName}.part")
        part.parentFile?.mkdirs()
        val connection = try {
            URI(spec.downloadUrl).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("Invalid model download URL '${spec.downloadUrl}'", e)
        }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) {
                throw LlmException("Model download failed with HTTP $status")
            }
            val totalBytes = connection.contentLengthLong
            val totalMb = if (totalBytes > 0) (totalBytes / (1024 * 1024)).toInt() else spec.approxSizeMb
            var downloadedBytes = 0L
            var lastReportedMb = -1

            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val currentMb = (downloadedBytes / (1024 * 1024)).toInt()
                        if (currentMb != lastReportedMb) {
                            lastReportedMb = currentMb
                            onProgress(currentMb, totalMb)
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw LlmException("Could not move downloaded model into ${target.absolutePath}")
            }
            return target
        } catch (e: IOException) {
            throw LlmException("Model download interrupted: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadStandaloneLlamaServerIfDesktop() {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        val zipUrl = when {
            os.contains("win") ->
                "https://github.com/ggml-org/llama.cpp/releases/download/b5092/llama-b5092-bin-win-avx2-x64.zip"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) ->
                "https://github.com/ggml-org/llama.cpp/releases/download/b5092/llama-b5092-bin-macos-arm64.zip"
            os.contains("mac") ->
                "https://github.com/ggml-org/llama.cpp/releases/download/b5092/llama-b5092-bin-macos-x64.zip"
            os.contains("linux") ->
                "https://github.com/ggml-org/llama.cpp/releases/download/b5092/llama-b5092-bin-ubuntu-x64.zip"
            else -> return
        }
        val conn = URI(zipUrl).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return
            val targetDir = binDir()
            ZipInputStream(conn.inputStream).use { zis ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val simpleName = File(entry.name).name
                        if (simpleName.startsWith("llama-server") ||
                            simpleName.endsWith(".dll") ||
                            simpleName.endsWith(".dylib") ||
                            simpleName.contains(".so")
                        ) {
                            val outFile = File(targetDir, simpleName)
                            FileOutputStream(outFile).use { fos ->
                                while (true) {
                                    val r = zis.read(buf)
                                    if (r < 0) break
                                    fos.write(buf, 0, r)
                                }
                            }
                            outFile.setExecutable(true)
                        }
                    }
                    zis.closeEntry()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MIN_VALID_MODEL_BYTES = 1024L * 1024L // 1 MB minimum sanity check
    }
}

/**
 * Automatically starts, monitors, and stops a local neural language model process in the background when
 * needed by Diplomacy chat, so the player never has to manually start or manage an external server.
 *
 * Supports:
 * 1. Standalone `llama-server` running a downloaded `.gguf` file inside `llm-models/` (port `11435`).
 * 2. Auto-spawned hidden background `ollama serve` process when Ollama is installed on the machine (port `11434`).
 * 3. Automatic model weight unloading (`keep_alive: 0`) and process termination when `DiplomacyScreen` closes.
 */
object BackgroundLlmRunner {
    const val STANDALONE_GGUF_URL = "http://127.0.0.1:11435/v1"

    private val lock = Any()

    @Volatile
    private var managedProcess: Process? = null

    @Volatile
    private var activeManagedUrl: String? = null

    @Volatile
    private var activeManagedLabel: String? = null

    @Volatile
    private var lastVerifiedAliveMs: Long = 0L

    /** Pluggable hooks for deterministic unit testing without spawning real OS processes in test suites. */
    @Volatile
    var testEndpointProbe: ((String) -> Boolean)? = null

    @Volatile
    var testProcessSpawner: ((List<String>, String) -> Boolean)? = null

    init {
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                stopManagedProcessSync()
            })
        } catch (_: Throwable) {
        }
    }

    val isManagingBackgroundProcess: Boolean
        get() = isProcessRunning(managedProcess) || (testProcessSpawner != null && activeManagedUrl != null)

    val managedStatusLabel: String?
        get() = activeManagedLabel

    /**
     * Ensures a local neural language model is running in the background and returns the active OpenAI-compatible
     * base URL (`http://127.0.0.1:11435/v1` for standalone `.gguf` or [configuredBaseUrl] for Ollama).
     */
    fun ensureRunning(
        configuredBaseUrl: String,
        spec: MobileModelSpec,
        modelManager: OnDeviceModelManager
    ): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        val currentManagedUrl = activeManagedUrl
        if (currentManagedUrl != null && isManagingBackgroundProcess) {
            if (now - lastVerifiedAliveMs < 5_000L || isEndpointAlive(currentManagedUrl)) {
                lastVerifiedAliveMs = System.currentTimeMillis()
                return currentManagedUrl
            }
        }

        // 1. Prefer standalone self-contained GGUF + llama-server in llm-models/ if both are downloaded
        val llamaBin = modelManager.findLlamaServerBinary()
        if (llamaBin != null && modelManager.isDownloaded(spec)) {
            if (isEndpointAlive(STANDALONE_GGUF_URL)) {
                activeManagedUrl = STANDALONE_GGUF_URL
                activeManagedLabel = "Local GGUF (${spec.fileName} — running in background)"
                lastVerifiedAliveMs = System.currentTimeMillis()
                return STANDALONE_GGUF_URL
            }
            val modelFile = modelManager.modelFile(spec)
            val cmd = listOf(
                llamaBin.absolutePath,
                "-m", modelFile.absolutePath,
                "--host", "127.0.0.1",
                "--port", "11435",
                "-c", "4096",
                "-ngl", "99"
            )
            if (spawnAndAwait(cmd, llamaBin.parentFile, STANDALONE_GGUF_URL, maxWaitMs = 25_000L)) {
                activeManagedUrl = STANDALONE_GGUF_URL
                activeManagedLabel = "Local GGUF (${spec.fileName} — auto-started in background)"
                lastVerifiedAliveMs = System.currentTimeMillis()
                return STANDALONE_GGUF_URL
            }
        }

        // 2. Check if the configured local endpoint (e.g. localhost:11434) is already awake
        if (isEndpointAlive(configuredBaseUrl)) {
            lastVerifiedAliveMs = System.currentTimeMillis()
            if (activeManagedLabel == null) {
                activeManagedLabel = "Local Model (${spec.ollamaTag} — active)"
            }
            return configuredBaseUrl
        }

        // 3. Auto-spawn local `ollama serve` in the background if Ollama is installed on the machine
        val ollamaBin = findOllamaExecutable()
        if (ollamaBin != null) {
            val cmd = listOf(ollamaBin.absolutePath, "serve")
            if (spawnAndAwait(cmd, ollamaBin.parentFile, configuredBaseUrl, maxWaitMs = 20_000L)) {
                activeManagedUrl = configuredBaseUrl
                activeManagedLabel = "Local Model (${spec.ollamaTag} — auto-started in background)"
                lastVerifiedAliveMs = System.currentTimeMillis()
                return configuredBaseUrl
            }
        } else if (testProcessSpawner != null) {
            val cmd = listOf("ollama", "serve")
            if (spawnAndAwait(cmd, null, configuredBaseUrl, maxWaitMs = 2_000L)) {
                activeManagedUrl = configuredBaseUrl
                activeManagedLabel = "Local Model (${spec.ollamaTag} — auto-started in background)"
                lastVerifiedAliveMs = System.currentTimeMillis()
                return configuredBaseUrl
            }
        }

        return configuredBaseUrl
    }

    /** Returns true if a local model endpoint is currently responding. */
    fun isEndpointAlive(baseUrl: String): Boolean {
        testEndpointProbe?.let { return it(baseUrl) }
        val trimmed = baseUrl.trimEnd('/')
        val probeUrls = listOf("$trimmed/models", "${trimmed.removeSuffix("/v1")}/health")
        for (url in probeUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 450
                conn.readTimeout = 800
                val code = conn.responseCode
                if (code in 200..299) return true
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    private fun spawnAndAwait(
        command: List<String>,
        workingDir: File?,
        targetBaseUrl: String,
        maxWaitMs: Long
    ): Boolean {
        testProcessSpawner?.let { spawner ->
            return spawner(command, targetBaseUrl)
        }
        return try {
            stopManagedProcessSync()
            val pb = ProcessBuilder(command)
            if (workingDir != null && workingDir.isDirectory) {
                pb.directory(workingDir)
            }
            pb.redirectErrorStream(true)
            val proc = pb.start()
            managedProcess = proc
            // Drain stdout/stderr on a daemon thread so OS pipe buffers never block the background LLM
            Thread {
                try {
                    val buf = ByteArray(4096)
                    proc.inputStream.use { input ->
                        while (input.read(buf) >= 0) {
                            // discard output
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                name = "Unciv-Background-LLM-Drain"
                isDaemon = true
                start()
            }

            val deadline = System.currentTimeMillis() + maxWaitMs
            while (System.currentTimeMillis() < deadline) {
                if (isEndpointAlive(targetBaseUrl)) return true
                if (!isProcessRunning(proc)) {
                    // Process exited early (or handed off to an existing instance); check one final time
                    return isEndpointAlive(targetBaseUrl)
                }
                Thread.sleep(250L)
            }
            isEndpointAlive(targetBaseUrl)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Unloads the active model weights from RAM/VRAM and terminates any background server process
     * that Unciv itself spawned when the player closes `DiplomacyScreen`.
     */
    fun stopManagedModelAsync(configuredBaseUrl: String, modelName: String) {
        if (testProcessSpawner != null) {
            stopManagedProcessSync()
            return
        }
        Thread {
            synchronized(lock) {
                unloadOllamaModelWeights(configuredBaseUrl, modelName)
                stopManagedProcessSync()
            }
        }.apply {
            name = "Unciv-Background-LLM-Shutdown"
            isDaemon = true
            start()
        }
    }

    fun stopManagedProcessSync() = synchronized(lock) {
        val proc = managedProcess
        managedProcess = null
        activeManagedUrl = null
        activeManagedLabel = null
        lastVerifiedAliveMs = 0L
        if (proc != null) {
            try {
                proc.destroy()
                for (i in 0 until 8) {
                    if (!isProcessRunning(proc)) break
                    Thread.sleep(100L)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun unloadOllamaModelWeights(baseUrl: String, modelName: String) {
        val rootUrl = baseUrl.trimEnd('/').removeSuffix("/v1")
        var conn: HttpURLConnection? = null
        try {
            conn = URI("$rootUrl/api/generate").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 800
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"model":${MiniJson.quote(modelName)},"keep_alive":0}"""
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Throwable) {
        } finally {
            conn?.disconnect()
        }
    }

    fun findOllamaExecutable(): File? {
        val isWin = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")
        val exeName = if (isWin) "ollama.exe" else "ollama"
        val explicitPaths = ArrayList<File>()
        if (isWin) {
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
                explicitPaths += File(it, "Programs\\Ollama\\ollama.exe")
            }
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let {
                explicitPaths += File(it, "AppData\\Local\\Programs\\Ollama\\ollama.exe")
            }
            System.getenv("ProgramFiles")?.takeIf { it.isNotBlank() }?.let {
                explicitPaths += File(it, "Ollama\\ollama.exe")
            }
        } else {
            explicitPaths += File("/opt/homebrew/bin/ollama")
            explicitPaths += File("/usr/local/bin/ollama")
            explicitPaths += File("/usr/bin/ollama")
        }
        for (f in explicitPaths) {
            if (f.isFile) return f
        }
        return findExecutableOnPath(exeName)
    }

    fun findExecutableOnPath(exeName: String): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        for (dirStr in pathEnv.split(File.pathSeparatorChar)) {
            if (dirStr.isBlank()) continue
            val candidate = File(dirStr.trim(), exeName)
            if (candidate.isFile) return candidate
        }
        return null
    }

    private fun isProcessRunning(process: Process?): Boolean {
        if (process == null) return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }
}

/**
 * An [LlmBackend] that runs entirely inside the app via a [NativeLlmBridge].
 * Automatically loads the selected [MobileModelSpec] when a chat message is sent, and unloads it
 * when [unload] is called (for example, when closing the diplomacy screen on a phone).
 */
class OnDeviceLlmBackend(
    private val bridgeProvider: () -> NativeLlmBridge?,
    private val modelManager: OnDeviceModelManager,
    private val selectedSpec: () -> MobileModelSpec
) : LlmBackend {
    private val lock = Any()
    private var loadedPath: String? = null

    override val isAvailable: Boolean
        get() = bridgeProvider() != null && modelManager.isDownloaded(selectedSpec())

    /** Pre-loads the selected GGUF model into native RAM when ChatTable opens so the first reply has zero cold-start delay. */
    fun warmUp() = synchronized(lock) {
        val bridge = bridgeProvider() ?: return@synchronized
        val spec = selectedSpec()
        if (!modelManager.isDownloaded(spec)) return@synchronized
        val path = modelManager.modelFile(spec).absolutePath
        if (!bridge.isLoaded || loadedPath != path) {
            if (bridge.isLoaded) bridge.unloadModel()
            bridge.loadModel(path)
            loadedPath = path
        }
    }

    override fun generate(request: LlmRequest): LlmResponse = synchronized(lock) {
        val bridge = bridgeProvider()
            ?: throw LlmException("No on-device native LLM runtime is registered on this platform.")
        val spec = selectedSpec()
        if (!modelManager.isDownloaded(spec)) {
            throw LlmException("On-device model '${spec.displayName}' is not downloaded yet. Use the Model button to download it.")
        }
        val path = modelManager.modelFile(spec).absolutePath
        if (!bridge.isLoaded || loadedPath != path) {
            if (bridge.isLoaded) bridge.unloadModel()
            bridge.loadModel(path)
            loadedPath = path
        }
        bridge.generate(request)
    }

    /** Frees the native model from mobile RAM when the player leaves the diplomacy screen. */
    fun unload() {
        synchronized(lock) {
            val bridge = bridgeProvider()
            if (bridge != null && bridge.isLoaded) {
                bridge.unloadModel()
            }
            loadedPath = null
        }
    }
}

/**
 * Native JNI bridge to in-process `llama.cpp` (`libunciv_llama.so` on Android arm64, `unciv_llama` static/dynamic
 * library on iOS/Desktop). Runs quantized `.gguf` models 100% locally inside the app process.
 */
object JniLlamaCppBridge : NativeLlmBridge {
    override val runtimeName: String = "llama.cpp-jni"

    @Volatile
    private var libraryLinked: Boolean = false

    @Volatile
    private var modelHandle: Long = 0L

    @Volatile
    private var activeModelPath: String = ""

    init {
        try {
            System.loadLibrary("unciv_llama")
            libraryLinked = true
        } catch (_: UnsatisfiedLinkError) {
            libraryLinked = false
        } catch (_: SecurityException) {
            libraryLinked = false
        }
    }

    val isNativeLibraryAvailable: Boolean get() = libraryLinked

    override val isLoaded: Boolean get() = modelHandle != 0L

    override fun loadModel(modelPath: String, contextSize: Int, gpuLayers: Int) {
        if (!libraryLinked) throw LlmException("Native on-device library 'unciv_llama' is not linked in this build.")
        if (modelHandle != 0L) unloadModel()
        activeModelPath = modelPath
        modelHandle = nativeLoadModel(modelPath, contextSize, gpuLayers)
        if (modelHandle == 0L) throw LlmException("Failed to load on-device GGUF model at $modelPath")
    }

    override fun unloadModel() {
        val h = modelHandle
        if (h != 0L && libraryLinked) {
            modelHandle = 0L
            nativeFreeModel(h)
        }
    }

    override fun generate(request: LlmRequest): LlmResponse {
        val h = modelHandle
        if (h == 0L || !libraryLinked) throw LlmException("On-device GGUF model is not loaded.")
        val prompt = buildChatMlPrompt(request)
        val out = nativeGenerate(h, prompt, request.maxTokens, request.temperature)
        return LlmResponse(out)
    }

    private fun buildChatMlPrompt(request: LlmRequest): String = buildString {
        val isGemma = activeModelPath.lowercase(Locale.ROOT).contains("gemma")
        val recentHistory = request.history.takeLast(4)
        if (isGemma) {
            append("<bos><start_of_turn>user\n").append(request.system).append("\n\n")
            for (msg in recentHistory) {
                val speaker = if (msg.role == "user") "Player" else "Leader"
                append(speaker).append(": ").append(msg.content).append("\n")
            }
            append("Player: ").append(request.user).append("<end_of_turn>\n<start_of_turn>model\n<|turn>thought\n<turn|>")
        } else {
            append("<|im_start|>system\n").append(request.system).append("<|im_end|>\n")
            for (msg in recentHistory) {
                append("<|im_start|>").append(msg.role).append("\n").append(msg.content).append("<|im_end|>\n")
            }
            append("<|im_start|>user\n").append(request.user).append("<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    @JvmStatic private external fun nativeLoadModel(modelPath: String, contextSize: Int, gpuLayers: Int): Long
    @JvmStatic private external fun nativeFreeModel(handle: Long)
    @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String
}
