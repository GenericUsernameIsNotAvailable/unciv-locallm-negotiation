package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.commitments.DealDraft
import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ClassicTradeAssessment
import com.unciv.logic.commitments.llm.ManipulationSignal
import com.unciv.logic.commitments.llm.MessageToneAnalysis
import com.unciv.logic.commitments.llm.MobileModelSpec
import com.unciv.logic.negotiation.ChatStore
import com.unciv.logic.negotiation.LlmSettings
import com.unciv.logic.negotiation.PreparedChatTurn
import com.unciv.logic.trade.Trade
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.utils.withGLContext

/**
 * A text conversation with an AI leader, shown on the right side of the diplomacy screen.
 * Supports both binding Commitment Deal cards and Classic Trade cards synced directly with [TradeTable].
 */
internal class ChatTable(
    private val diplomacyScreen: DiplomacyScreen,
    private val aiCiv: Civilization
) : Table(BaseScreen.skin) {
    private val humanCiv = diplomacyScreen.viewingCiv
    private val negotiation = humanCiv.gameInfo.negotiation
    private val session: ChatSession = negotiation.chatWith(aiCiv, humanCiv)
    private val leaderName = aiCiv.nation.leaderName.ifEmpty { aiCiv.civName }

    private val transcriptTable = Table().apply { defaults().pad(4f).left() }
    private val scrollPane = ScrollPane(transcriptTable, BaseScreen.skin).apply {
        setFadeScrollBars(false)
        setScrollingDisabled(true, false)
    }
    private val dealCardWrapper = Table()
    private val input = UncivTextField("Say something or propose a deal")
    private val sendButton = "Send".toTextButton()
    private var waitingForReply = false
    private var pendingDeal: DealDraft? = null
    private var pendingClassicTrade: Trade? = null
    private var pendingClassicAccepted: Boolean = false
    /** Shown only for the latest turn right below the newest reply, then cleared on the next message. */
    private val latestTurnNotices = ArrayList<String>()

    private val modelLabel = "AI: ${LlmSettings.statusDescription()}".toLabel(fontColor = Color.GRAY, fontSize = 14)

    init {
        pad(10f)
        add("Talking with [$leaderName]".toLabel(fontSize = 24)).colspan(2).row()
        add(modelLabel).colspan(2).padBottom(5f).row()
        addSeparator()
        add(scrollPane).colspan(2).width(CHAT_WIDTH).height(CHAT_HEIGHT).padTop(5f).row()
        add(dealCardWrapper).colspan(2).width(CHAT_WIDTH).padTop(4f).row()
        add(input).width(CHAT_WIDTH - 110f).padTop(5f)
        add(sendButton).width(100f).padLeft(10f).padTop(5f).row()
        val bottomRow = Table().apply { defaults().pad(5f) }
        bottomRow.add("Back".toTextButton().apply {
            onClick { diplomacyScreen.updateRightSide(aiCiv) }
        })
        bottomRow.add("AI Model Setup".toTextButton().apply {
            onClick { openModelSetupPopup() }
        })
        add(bottomRow).colspan(2).padTop(6f).row()

        for (outboxLine in negotiation.drainOutboxLinesFor(aiCiv, humanCiv)) {
            session.addLeaderInitiative(outboxLine)
        }
        val pairKey = "${aiCiv.civID}>${humanCiv.civID}"
        ChatStore.pendingProactiveDealFor(pairKey)?.let { proactive ->
            pendingDeal = proactive
            ChatStore.setPendingProactiveDeal(pairKey, null)
        }
        if (negotiation.isAudienceEndedForTurn(aiCiv, humanCiv)) {
            lockAudienceForTurn()
        }
        sendButton.onClick { send() }
        input.keyShortcuts.add(Input.Keys.ENTER) { send() }
        refresh()

        Concurrency.run("Warmup local LLM") {
            LlmSettings.warmUpBackgroundModel()
            launchOnGLThread {
                modelLabel.setText("AI: ${LlmSettings.statusDescription()}")
            }
        }
    }

    private fun lockAudienceForTurn() {
        input.isDisabled = true
        input.messageText = "Audience ended by $leaderName until next turn."
        sendButton.disable()
        pendingDeal = null
        pendingClassicTrade = null
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty() || waitingForReply || negotiation.isAudienceEndedForTurn(aiCiv, humanCiv)) return
        input.text = ""
        pendingDeal = null
        pendingClassicTrade = null
        latestTurnNotices.clear()
        session.say(text)
        negotiation.saveChatData()
        waitingForReply = true
        sendButton.disable()
        refresh()

        val semCtx = negotiation.captureSemanticContext(aiCiv, humanCiv, text)

        Concurrency.run("Leader chat") {
            var prepared: PreparedChatTurn? = null
            var reply: ChatSession.Reply? = null
            try {
                val semanticHint = negotiation.runSemanticParserIfNeeded(semCtx)
                prepared = withGLContext {
                    negotiation.prepareChatTurn(aiCiv, humanCiv, text, semanticHint)
                }
                val p = prepared
                reply = session.reply(
                    profile = p.profile,
                    facts = p.facts,
                    toneNote = p.tone.promptNote,
                    proposalDirective = p.directive,
                    proposalAccepted = p.allowsAgreement
                )
            } catch (e: Throwable) {
                reply = ChatSession.Reply.Failed("Chat error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                launchOnGLThread {
                    waitingForReply = false
                    val p = prepared
                    if (p != null) {
                        describeToneConsequence(p.tone, p.denouncedNow)?.let { latestTurnNotices += it }
                        p.intelReport?.uiNotice?.let { latestTurnNotices += it }
                    }
                    diplomacyScreen.updateLeftSideTable(aiCiv)
                    negotiation.saveChatData()

                    if (p?.tone?.walkedOut == true || negotiation.isAudienceEndedForTurn(aiCiv, humanCiv)) {
                        lockAudienceForTurn()
                    } else {
                        sendButton.enable()
                        val acceptedDraft = p?.confirmableCommitmentDraft
                        val classicTrade = p?.executableTrade
                        val classicCanConfirm = p?.classicTrade is ClassicTradeAssessment.Accepted ||
                            p?.classicTrade is ClassicTradeAssessment.Counteroffered
                        if (reply is ChatSession.Reply.Said) {
                            if (acceptedDraft != null) {
                                pendingDeal = acceptedDraft
                                pendingClassicTrade = null
                            } else if (classicTrade != null) {
                                pendingClassicTrade = classicTrade
                                pendingClassicAccepted = classicCanConfirm
                                pendingDeal = null
                            }
                        }
                    }
                    refresh(failure = (reply as? ChatSession.Reply.Failed)?.reason)
                }
            }
        }
    }

    private fun refresh(failure: String? = null) {
        transcriptTable.clear()
        for (message in session.messages) {
            addLine(if (message.fromPlayer) "You" else leaderName, message.text, message.fromPlayer)
        }
        for (notice in latestTurnNotices) {
            addLine("", notice, fromPlayer = false, color = Color.GOLD)
        }
        if (waitingForReply) addLine(leaderName, "…", false)
        if (failure != null) addLine("", "($failure)", false, Color.GRAY)
        rebuildDealCard()
        scrollToEnd()
        Concurrency.run("Scroll chat") { launchOnGLThread { scrollToEnd() } }
    }

    private fun rebuildDealCard() {
        dealCardWrapper.clear()
        val draft = pendingDeal
        if (draft != null) {
            buildCommitmentCard(draft)
            return
        }
        val trade = pendingClassicTrade
        if (trade != null) {
            buildClassicTradeCard(trade, pendingClassicAccepted)
        }
    }

    private fun buildCommitmentCard(draft: DealDraft) {
        val card = Table(BaseScreen.skin).apply { defaults().pad(4f).left() }
        card.add("Proposed Commitment Deal (ready to confirm):".toLabel(fontColor = Color.GOLD, fontSize = 16)).colspan(2).row()
        for (item in draft.items) {
            val bullet = "- ${item.describe()}".toLabel(fontColor = Color.LIGHT_GRAY, fontSize = 14)
            bullet.wrap = true
            card.add(bullet).width(CHAT_WIDTH - 40f).colspan(2).row()
        }
        val confirmBtn = "Confirm Deal".toTextButton().apply {
            onClick {
                val ok = negotiation.confirmChatDeal(draft)
                pendingDeal = null
                latestTurnNotices.clear()
                latestTurnNotices += if (ok) "Deal confirmed and now active." else "The deal could not be activated."
                refresh()
            }
        }
        val cancelBtn = "Cancel".toTextButton().apply {
            onClick {
                pendingDeal = null
                refresh()
            }
        }
        card.add(confirmBtn).padRight(10f)
        card.add(cancelBtn).row()
        dealCardWrapper.add(card).width(CHAT_WIDTH)
    }

    private fun buildClassicTradeCard(trade: Trade, canConfirmImmediately: Boolean) {
        val card = Table(BaseScreen.skin).apply { defaults().pad(4f).left() }
        val title = if (canConfirmImmediately) "Agreed Trade Terms:" else "Staged Trade Terms (not yet acceptable):"
        card.add(title.toLabel(fontColor = Color.GOLD, fontSize = 16)).colspan(3).row()
        val summary = negotiation.describeHumanSideTrade(trade, humanCiv.civName, aiCiv.civName)
            .toLabel(fontColor = Color.LIGHT_GRAY, fontSize = 14)
        summary.wrap = true
        card.add(summary).width(CHAT_WIDTH - 40f).colspan(3).row()

        if (canConfirmImmediately) {
            val confirmBtn = "Confirm Trade".toTextButton().apply {
                onClick {
                    val ok = negotiation.executeAcceptedTrade(aiCiv, humanCiv, trade)
                    pendingClassicTrade = null
                    latestTurnNotices.clear()
                    latestTurnNotices += if (ok) "Trade completed." else "The trade is no longer valid."
                    diplomacyScreen.updateLeftSideTable(aiCiv)
                    refresh()
                }
            }
            card.add(confirmBtn).padRight(8f)
        }
        val openTableBtn = "Open in Trade Table".toTextButton().apply {
            onClick {
                val table = diplomacyScreen.setTrade(aiCiv)
                table.tradeView.setStagedTrade(trade)
                table.offerColumnsTable.update()
                table.enableOfferButton(true)
            }
        }
        val cancelBtn = "Cancel".toTextButton().apply {
            onClick {
                pendingClassicTrade = null
                refresh()
            }
        }
        card.add(openTableBtn).padRight(8f)
        card.add(cancelBtn).row()
        dealCardWrapper.add(card).width(CHAT_WIDTH)
    }

    private fun describeToneConsequence(tone: MessageToneAnalysis, denouncedNow: Boolean = false): String? {
        if (tone.opinionDelta == 0f && !tone.walkedOut && !denouncedNow) return null
        val deltaInt = tone.opinionDelta.toInt()
        val signed = if (deltaInt >= 0) "+$deltaInt" else "$deltaInt"
        if (tone.walkedOut) {
            val denounceText = if (denouncedNow) " $leaderName has publicly DENOUNCED your civilization!" else ""
            return "[AUDIENCE TERMINATED ($signed opinion): $leaderName stormed out over your repeated insults!$denounceText]"
        }
        return when (tone.manipulation) {
            ManipulationSignal.FalseOffer ->
                "[Diplomatic penalty ($signed opinion): Caught offering wealth or resources you do not possess — ${tone.falseOfferReasons.joinToString("; ")}]"
            ManipulationSignal.BrownNosing ->
                "[Diplomatic penalty ($signed opinion): Leader detected manipulative flattery / brown-nosing]"
            ManipulationSignal.Hypocrisy ->
                "[Diplomatic penalty ($signed opinion): Praise contradicted your hostile diplomatic record]"
            ManipulationSignal.FabricatedClaim ->
                "[Diplomatic penalty ($signed opinion): Claimed a past offer or promise that never happened]"
            ManipulationSignal.None ->
                if (tone.opinionDelta < 0f) {
                    val warning = if (tone.hostileStrikes == 2) " — one more insult will terminate this audience!" else ""
                    "[Diplomatic penalty ($signed opinion): Insulting or threatening language (strike ${tone.hostileStrikes}/3)$warning]"
                } else "[Diplomatic bonus ($signed opinion): Respectful demeanour]"
        }
    }

    private fun openModelSetupPopup() {
        val popup = Popup(diplomacyScreen)
        popup.addGoodSizedLabel("On-Device AI Model Setup (Runs 100% Locally on Device)", size = 20).colspan(2).row()
        popup.addGoodSizedLabel("Active: ${LlmSettings.statusDescription()}", size = 14).colspan(2).padBottom(8f).row()

        for (spec in MobileModelSpec.CATALOG) {
            val downloaded = LlmSettings.modelManager.isDownloaded(spec)
            val selectBtn = "Use ${spec.ollamaTag}".toTextButton().apply {
                onClick {
                    LlmSettings.modelOverride = spec.ollamaTag
                    modelLabel.setText("AI: ${LlmSettings.statusDescription()}")
                    popup.close()
                }
            }
            val dlText = if (downloaded) "Downloaded (${spec.approxSizeMb} MB)" else "Download On-Device (${spec.approxSizeMb} MB)"
            val dlBtn = dlText.toTextButton().apply {
                if (downloaded) disable()
                onClick {
                    disable()
                    setText("Downloading...")
                    Concurrency.run("Download LLM") {
                        try {
                            LlmSettings.modelManager.download(spec) { doneMb, totalMb ->
                                launchOnGLThread { setText("Downloading $doneMb / $totalMb MB") }
                            }
                            launchOnGLThread {
                                setText("Downloaded (${spec.approxSizeMb} MB)")
                                modelLabel.setText("AI: ${LlmSettings.statusDescription()}")
                            }
                        } catch (e: Exception) {
                            launchOnGLThread {
                                enable()
                                setText("Failed: retry")
                                latestTurnNotices += "(Model download failed: ${e.message})"
                                refresh()
                            }
                        }
                    }
                }
            }
            popup.add(selectBtn).left().pad(4f)
            popup.add(dlBtn).right().pad(4f).row()
        }
        popup.addCloseButton()
        popup.open(force = true)
    }

    private fun scrollToEnd() {
        transcriptTable.invalidateHierarchy()
        scrollPane.layout()
        scrollPane.scrollPercentY = 1f
        scrollPane.updateVisualScroll()
    }

    private fun addLine(speaker: String, text: String, fromPlayer: Boolean, color: Color = if (fromPlayer) Color.SKY else Color.WHITE) {
        val line = (if (speaker.isEmpty()) text else "$speaker: $text").toLabel(fontColor = color)
        line.wrap = true
        line.setAlignment(Align.left)
        transcriptTable.add(line).width(CHAT_WIDTH - 30f).row()
    }

    companion object {
        private const val CHAT_WIDTH = 520f
        private const val CHAT_HEIGHT = 340f
    }
}
