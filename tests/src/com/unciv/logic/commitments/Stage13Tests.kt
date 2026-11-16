package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.BackgroundLlmRunner
import com.unciv.logic.commitments.llm.MobileModelSpec
import com.unciv.logic.commitments.llm.OnDeviceModelManager
import org.junit.After
import org.junit.Assert
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Stage13Tests {

    @After
    fun tearDown() {
        BackgroundLlmRunner.testEndpointProbe = null
        BackgroundLlmRunner.testProcessSpawner = null
        BackgroundLlmRunner.stopManagedProcessSync()
    }

    @Test
    fun `BackgroundLlmRunner auto-starts local neural model in background when server is turned off and stops on close`() {
        val tempDir = Files.createTempDirectory("unciv-llm-bg-test").toFile()
        try {
            val manager = OnDeviceModelManager { tempDir }
            val spec = MobileModelSpec.CATALOG.first()

            var serverAwake = false
            val spawnedCommands = ArrayList<List<String>>()

            BackgroundLlmRunner.testEndpointProbe = { _ -> serverAwake }
            BackgroundLlmRunner.testProcessSpawner = { cmd, _ ->
                spawnedCommands += cmd
                serverAwake = true
                true
            }

            // 1. Server is initially OFF; ensureRunning spawns the background LLM process automatically
            val url = BackgroundLlmRunner.ensureRunning("http://localhost:11434/v1", spec, manager)
            Assert.assertEquals("http://localhost:11434/v1", url)
            Assert.assertTrue("Expected background LLM process to be spawned", spawnedCommands.isNotEmpty())
            Assert.assertTrue(BackgroundLlmRunner.isManagingBackgroundProcess)
            Assert.assertTrue(BackgroundLlmRunner.managedStatusLabel.orEmpty().contains("auto-started in background"))

            // 2. Subsequent call while Diplomacy is still open reuses the running background process without respawning
            val countAfterFirst = spawnedCommands.size
            val url2 = BackgroundLlmRunner.ensureRunning("http://localhost:11434/v1", spec, manager)
            Assert.assertEquals("http://localhost:11434/v1", url2)
            Assert.assertEquals(countAfterFirst, spawnedCommands.size)

            // 3. Closing Diplomacy stops the managed background process
            BackgroundLlmRunner.stopManagedModelAsync("http://localhost:11434/v1", spec.ollamaTag)
            Assert.assertFalse(BackgroundLlmRunner.isManagingBackgroundProcess)
            Assert.assertNull(BackgroundLlmRunner.managedStatusLabel)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `BackgroundLlmRunner launches standalone llama-server on downloaded GGUF file when present in llm-models`() {
        val tempDir = Files.createTempDirectory("unciv-gguf-bg-test").toFile()
        try {
            val manager = OnDeviceModelManager { tempDir }
            val spec = MobileModelSpec.CATALOG.first()

            // Create a fake standalone llama-server binary (> 10 KB) and fake downloaded GGUF model (> 1 MB)
            val isWin = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            val llamaExe = File(manager.binDir(), if (isWin) "llama-server.exe" else "llama-server")
            llamaExe.writeBytes(ByteArray(16 * 1024) { 1 })
            llamaExe.setExecutable(true)

            val ggufFile = manager.modelFile(spec)
            ggufFile.writeBytes(ByteArray(1024 * 1024 + 512) { 2 })

            var ggufServerAwake = false
            val spawnedCommands = ArrayList<List<String>>()

            BackgroundLlmRunner.testEndpointProbe = { url ->
                url == BackgroundLlmRunner.STANDALONE_GGUF_URL && ggufServerAwake
            }
            BackgroundLlmRunner.testProcessSpawner = { cmd, targetUrl ->
                spawnedCommands += cmd
                if (targetUrl == BackgroundLlmRunner.STANDALONE_GGUF_URL) {
                    ggufServerAwake = true
                }
                true
            }

            val activeUrl = BackgroundLlmRunner.ensureRunning("http://localhost:11434/v1", spec, manager)
            Assert.assertEquals(BackgroundLlmRunner.STANDALONE_GGUF_URL, activeUrl)
            Assert.assertEquals(1, spawnedCommands.size)
            Assert.assertTrue(spawnedCommands.single().any { it.contains("llama-server") })
            Assert.assertTrue(spawnedCommands.single().any { it.endsWith(spec.fileName) })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
