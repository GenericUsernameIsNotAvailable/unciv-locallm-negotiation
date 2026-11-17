package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.commitments.llm.MobileModelSpec
import com.unciv.logic.negotiation.LlmSettings
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

internal class AiModelsTab(
    optionsPopup: OptionsPopup
) : OptionsPopupTab(optionsPopup) {

    private val statusLabel = "Active: ${LlmSettings.statusDescription()}".toLabel(Color.GOLD, 16)
    private val folderLabel = "Storage: ${LlmSettings.modelManager.modelsDir().absolutePath}".toLabel(Color.GRAY, 12)

    override fun lateInitialize() {
        rebuildContent()
        super.lateInitialize()
    }

    private fun rebuildContent() {
        clear()
        addHeader("On-Device AI Language Models")
        statusLabel.setText("Active: ${LlmSettings.statusDescription()}")
        folderLabel.setText("Storage: ${LlmSettings.modelManager.modelsDir().absolutePath}")
        add(statusLabel).colspan(2).center().row()
        add(folderLabel).colspan(2).center().padBottom(8f).row()
        addSeparator()

        for (spec in MobileModelSpec.CATALOG) {
            val isDownloaded = LlmSettings.modelManager.isDownloaded(spec)
            val isSelected = LlmSettings.current().model.equals(spec.ollamaTag, ignoreCase = true) ||
                LlmSettings.current().model.equals(spec.id, ignoreCase = true)

            val titleColor = if (isSelected) Color.LIME else Color.WHITE
            add(spec.displayName.toLabel(titleColor, 15)).colspan(2).left().padTop(6f).row()

            val selectLabel = if (isSelected) "Selected (${spec.ollamaTag})" else "Use ${spec.ollamaTag}"
            val selectBtn = selectLabel.toTextButton().apply {
                if (isSelected) disable()
                onClick {
                    LlmSettings.modelOverride = spec.ollamaTag
                    if (isDownloaded) {
                        Concurrency.run("Preload selected LLM") {
                            LlmSettings.warmUpBackgroundModel()
                            launchOnGLThread { rebuildContent() }
                        }
                    } else {
                        rebuildContent()
                    }
                }
            }

            val dlText = if (isDownloaded) "Downloaded (${spec.approxSizeMb} MB)" else "Download (${spec.approxSizeMb} MB)"
            val dlBtn = dlText.toTextButton().apply {
                if (isDownloaded) disable()
                onClick {
                    disable()
                    setText("Starting download...")
                    Concurrency.run("Settings Download LLM") {
                        try {
                            LlmSettings.modelManager.download(spec) { doneMb, totalMb ->
                                launchOnGLThread { setText("Downloading $doneMb / $totalMb MB") }
                            }
                            LlmSettings.modelOverride = spec.ollamaTag
                            launchOnGLThread { rebuildContent() }
                        } catch (e: Exception) {
                            launchOnGLThread {
                                enable()
                                setText("Retry Download (${spec.approxSizeMb} MB)")
                                statusLabel.setText("Download failed: ${e.message}")
                            }
                        }
                    }
                }
            }

            add(selectBtn).left().pad(4f)
            add(dlBtn).right().pad(4f).row()

            if (isDownloaded) {
                val deleteBtn = "Delete ${spec.ollamaTag} File".toTextButton().apply {
                    onClick {
                        LlmSettings.onDiplomacyClosed()
                        LlmSettings.modelManager.deleteModel(spec)
                        rebuildContent()
                    }
                }
                add(deleteBtn).colspan(2).right().padBottom(4f).row()
            }
            addSeparator()
        }
    }
}
