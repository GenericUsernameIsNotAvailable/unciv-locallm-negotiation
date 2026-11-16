package com.unciv.ui.screens.devconsole

import com.unciv.logic.commitments.AcceptResult
import com.unciv.logic.commitments.DealBuilders
import com.unciv.logic.commitments.DealDraft
import com.unciv.logic.commitments.ProposalResult
import com.unciv.logic.commitments.llm.ChatProbe
import com.unciv.logic.commitments.llm.MobileModelSpec
import com.unciv.logic.negotiation.LlmSettings
import com.unciv.utils.Concurrency

/**
 * Developer commands for the commitment engine. Each deal command proposes the deal and immediately
 * confirms it on behalf of both parties, so the engine can be exercised before there is a chat UI.
 * Civ parameters are civIDs, like the rest of the console.
 */
internal class ConsoleNegotiationCommands : ConsoleCommandNode {
    override val subcommands = hashMapOf<String, ConsoleCommand>(
        "list" to ConsoleAction("negotiation list") { console, _ ->
            val manager = console.gameInfo.negotiation.manager
            val message = buildString {
                appendLine("Turn ${console.gameInfo.turns}")
                for (c in manager.allCommitments()) appendLine("#${c.id} [${c.status}] ${c.describe()}")
                appendLine("-- decision log (last 10) --")
                for (e in manager.log.entries.takeLast(10)) {
                    val reasons = e.reasons.joinToString { it.code.name }
                    appendLine("turn ${e.turn}: ${e.kind} ${e.actor}->${e.other} #${e.commitmentId ?: "-"} $reasons")
                }
            }
            DevConsoleResponse.hint(message)
        },
        "outbox" to ConsoleAction("negotiation outbox") { console, _ ->
            val messages = console.gameInfo.negotiation.manager.drainOutbox()
            DevConsoleResponse.hint(
                if (messages.isEmpty()) "(empty)"
                else messages.joinToString("\n") { "turn ${it.turn}: ${it.from} -> ${it.to}: ${it.kind} (#${it.commitmentId})" }
            )
        },
        "model" to ConsoleAction("negotiation model [name]") { _, params ->
            params.getOrNull(0)?.let { LlmSettings.modelOverride = it.originalUnquoted() }
            DevConsoleResponse.hint("Active AI setup: ${LlmSettings.statusDescription()}. Available mobile presets: ${MobileModelSpec.CATALOG.joinToString { it.ollamaTag }}")
        },
        "url" to ConsoleAction("negotiation url [http://host:11434/v1]") { _, params ->
            params.getOrNull(0)?.let { LlmSettings.urlOverride = it.originalUnquoted() }
            DevConsoleResponse.hint("Active AI server URL: ${LlmSettings.current().baseUrl}")
        },
        "strategy" to ConsoleAction("negotiation strategy [on|off|status]") { console, params ->
            when (params.getOrNull(0)?.originalUnquoted()?.lowercase()) {
                "on" -> LlmSettings.strategicAiEnabled = true
                "off" -> LlmSettings.strategicAiEnabled = false
            }
            val aiCivs = console.gameInfo.civilizations.filter { it.isAI() && it.isMajorCiv && !it.isDefeated() }
            val lines = aiCivs.joinToString("\n") { civ ->
                val posture = console.gameInfo.negotiation.evaluateStrategicPosture(civ)
                "- ${civ.civName} (${civ.nation.leaderName}): ${posture.name} — ${posture.description}"
            }
            DevConsoleResponse.hint("Hybrid Strategic AI: ${if (LlmSettings.strategicAiEnabled) "ENABLED" else "DISABLED"}\n$lines")
        },
        "ondevice" to ConsoleAction("negotiation ondevice [status|unload]") { _, params ->
            when (params.getOrNull(0)?.originalUnquoted()?.lowercase()) {
                "unload" -> {
                    LlmSettings.onDiplomacyClosed()
                    DevConsoleResponse.hint("Unloaded on-device model from memory.")
                }
                else -> {
                    val lines = MobileModelSpec.CATALOG.joinToString("\n") { spec ->
                        val state = if (LlmSettings.modelManager.isDownloaded(spec)) "DOWNLOADED" else "not downloaded"
                        "- ${spec.id} (${spec.ollamaTag}, ~${spec.approxSizeMb} MB, min ${spec.recommendedRamGb} GB RAM): $state"
                    }
                    DevConsoleResponse.hint("Mode: ${LlmSettings.statusDescription()}\nModels folder: ${LlmSettings.modelManager.modelsDir().absolutePath}\n$lines")
                }
            }
        },
        "thinking" to ConsoleAction("negotiation thinking [on|off]") { console, params ->
            when (params.getOrNull(0)?.originalUnquoted()?.lowercase()) {
                "off" -> LlmSettings.reasoningEffort = "none"
                "on" -> LlmSettings.reasoningEffort = null
                null -> {}
                else -> throw ConsoleErrorException("Use 'negotiation thinking on' or 'negotiation thinking off'")
            }
            DevConsoleResponse.hint(
                if (LlmSettings.reasoningEffort == null) "Thinking: left to the model's default"
                else "Thinking: asked to be off (the server may or may not honour this; models that cannot think ignore it)"
            )
        },
        "probe" to ConsoleAction("negotiation probe <civName>") { console, params ->
            val ai = console.getCivByName(params[0])
            if (!ai.isAI() || !ai.isMajorCiv) throw ConsoleErrorException("Pick an AI major civ")
            val human = console.screen.viewingCiv
            val backend = LlmSettings.createBackend()
            val (profile, facts) = console.gameInfo.negotiation.chatContext(ai, human)
            // The model answers on a background thread; the report goes to the terminal and to llm-probe.txt.
            val endpoint = LlmSettings.current()
            val reasoning = LlmSettings.reasoningEffort
            val file = java.io.File("llm-probe.txt") // the game runs from the android/assets folder, so that is where this lands
            Concurrency.run("Leader probe") {
                val header = "Leader probe for ${profile.leaderName} (model ${endpoint.model}, thinking ${if (reasoning == null) "default" else "off"})"
                val text = header + "\n" + ChatProbe.describeProfile(profile) + "\n" + ChatProbe.format(ChatProbe.run(backend, profile, facts, reasoning))
                println(text)
                file.writeText(text, Charsets.UTF_8)
            }
            DevConsoleResponse.hint("Probing ${profile.leaderName} with ${endpoint.model}. Results go to the terminal and to ${file.absolutePath} within a minute or two")
        },
        "probeall" to ConsoleAction("negotiation probeall") { console, _ ->
            val human = console.screen.viewingCiv
            val leaders = console.gameInfo.civilizations.filter { it.isAI() && it.isMajorCiv && !it.isDefeated() && human.knows(it) }
            if (leaders.isEmpty()) throw ConsoleErrorException("You have not met any AI leaders. Try 'diplomacy meet <you> <civ>' first")
            val backend = LlmSettings.createBackend()
            val endpoint = LlmSettings.current()
            val reasoning = LlmSettings.reasoningEffort
            // All game state is read here on the GL thread; the model answers on a background thread.
            val contexts = leaders.map { console.gameInfo.negotiation.chatContext(it, human) }
            val file = java.io.File("llm-probe-all.txt")
            Concurrency.run("Leader probe all") {
                val text = buildString {
                    appendLine("Probe of ${contexts.size} leaders (model ${endpoint.model}, thinking ${if (reasoning == null) "default" else "off"})")
                    for ((profile, facts) in contexts) {
                        appendLine()
                        appendLine("===== ${profile.leaderName} of ${profile.nationName} =====")
                        appendLine(ChatProbe.describeProfile(profile))
                        appendLine(ChatProbe.format(ChatProbe.run(backend, profile, facts, reasoning, ChatProbe.shortLines)))
                    }
                }
                println(text)
                file.writeText(text, Charsets.UTF_8)
            }
            DevConsoleResponse.hint("Probing ${contexts.size} leaders with ${endpoint.model}. Results go to ${file.absolutePath} (and the terminal) when done")
        },
        "noattack" to ConsoleAction("negotiation noattack <civName> <civName> <turns>") { console, params ->
            activate(console, DealBuilders.noAttack(civId(console, params[0]), civId(console, params[1]), params[2].toInt(), now(console)))
        },
        "nosettle" to ConsoleAction("negotiation nosettle <civName> <civName> <turns>") { console, params ->
            activate(console, DealBuilders.noSettleNear(civId(console, params[0]), civId(console, params[1]), params[2].toInt(), now(console)))
        },
        "loan" to ConsoleAction("negotiation loan <civName> <civName> <principal> <repayPerTurn> <turns>") { console, params ->
            activate(
                console,
                DealBuilders.loan(
                    lender = civId(console, params[0]), borrower = civId(console, params[1]),
                    principal = params[2].toInt(), repayPerTurn = params[3].toInt(), turns = params[4].toInt(), now = now(console)
                )
            )
        },
        "subsidy" to ConsoleAction("negotiation subsidy <civName> <civName> <civName> <goldPerTurn> <turns>") { console, params ->
            activate(
                console,
                DealBuilders.proxyWarSubsidy(
                    payer = civId(console, params[0]), fighter = civId(console, params[1]), target = civId(console, params[2]),
                    goldPerTurn = params[3].toInt(), turns = params[4].toInt(), now = now(console)
                )
            )
        },
        "war" to ConsoleAction("negotiation war <civName> <civName> <civName> <inTurns> [minForceRatio]") { console, params ->
            val now = now(console)
            activate(
                console,
                DealBuilders.conditionalWar(
                    committer = civId(console, params[0]), partner = civId(console, params[1]), target = civId(console, params[2]),
                    notBefore = now + 1, byTurn = now + params[3].toInt(),
                    minForceRatio = params.getOrNull(4)?.toFloat()
                )
            )
        },
    )

    private fun now(console: DevConsolePopup) = console.gameInfo.turns

    private fun civId(console: DevConsolePopup, param: CliInput) = console.getCivByName(param).civID

    private fun activate(console: DevConsolePopup, draft: DealDraft): DevConsoleResponse {
        val manager = console.gameInfo.negotiation.manager
        when (val proposal = manager.propose(draft)) {
            is ProposalResult.Rejected ->
                throw ConsoleErrorException(proposal.errors.joinToString("; ") { it.message })
            is ProposalResult.Proposed -> {
                val result = manager.accept(proposal.dealId, draft.counterparty)
                if (result != AcceptResult.Activated) throw ConsoleErrorException("Could not activate the deal: $result")
            }
        }
        return DevConsoleResponse.OK
    }
}
