package com.unciv.logic.negotiation

import com.unciv.Constants
import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest

sealed interface ImmediateTradeResult {
    /** The trade has been carried out. */
    data object Accepted : ImmediateTradeResult

    /** The AI would agree to [trade] instead. Written from the human's side, ready to stage and offer again. */
    data class Counteroffer(val trade: Trade) : ImmediateTradeResult

    data object Declined : ImmediateTradeResult

    /** The staged trade is no longer possible (e.g. a resource is gone). */
    data object Invalid : ImmediateTradeResult

    /** The leader has refused too often this turn; see [com.unciv.logic.commitments.HagglingLimit]. */
    data object OutOfPatience : ImmediateTradeResult
}

/**
 * Lets an AI civ decide on a human's trade offer at once instead of on its own turn.
 * This mirrors the body of [TradeAutomation.respondToTradeRequests] for a single request, using the same
 * evaluators, so the AI's judgement is unchanged; only the moment of decision moves. If that function
 * changes, mirror the change here.
 */
internal object ImmediateTrade {
    /** @param humanSideTrade the trade as the human staged it: ourOffers are what the human gives. */
    fun respond(ai: Civilization, human: Civilization, humanSideTrade: Trade): ImmediateTradeResult {
        // The AI sees the same trade from its own side.
        val request = TradeRequest(human.civID, humanSideTrade.reverse())
        val evaluation = TradeEvaluation()
        if (!evaluation.isTradeValid(request.trade, ai, human)) return ImmediateTradeResult.Invalid
        if (violatesActiveCommitment(ai, human, request.trade)) {
            request.decline(ai)
            return ImmediateTradeResult.Declined
        }

        val tradeLogic = TradeLogic(ai, human)
        tradeLogic.currentTrade.set(request.trade)
        if (evaluation.isTradeAcceptable(tradeLogic.currentTrade, ai, human)) {
            tradeLogic.acceptTrade()
            ai.gameInfo.negotiation.onTradeCompleted(ai, human)
            return ImmediateTradeResult.Accepted
        }

        val counteroffer = TradeAutomation.getCounteroffer(ai, request)
        if (counteroffer != null && !violatesActiveCommitment(ai, human, counteroffer.trade.reverse())) {
            return ImmediateTradeResult.Counteroffer(counteroffer.trade) // already the human's side
        }
        request.decline(ai)
        return ImmediateTradeResult.Declined
    }

    /** True if accepting [aiSideTrade] would force [ai] or [partner] to break an active war or peace commitment. */
    internal fun violatesActiveCommitment(ai: Civilization, partner: Civilization, aiSideTrade: Trade): Boolean {
        val negotiation = ai.gameInfo.negotiation
        if (!aiSideTrade.isPeaceTreaty() && (negotiation.forbidsTrade(ai, partner) || negotiation.forbidsTrade(partner, ai))) {
            return true
        }
        if (aiSideTrade.isPeaceTreaty() && (negotiation.forbidsPeace(ai, partner) || negotiation.forbidsPeace(partner, ai))) {
            return true
        }
        return offersForbiddenWarOrPeace(ai, aiSideTrade.ourOffers, negotiation) ||
            offersForbiddenWarOrPeace(partner, aiSideTrade.theirOffers, negotiation)
    }

    private fun offersForbiddenWarOrPeace(
        committer: Civilization,
        offers: Iterable<TradeOffer>,
        negotiation: NegotiationSystem
    ): Boolean {
        for (offer in offers) {
            if (offer.type == TradeOfferType.WarDeclaration) {
                val target = committer.gameInfo.getCivilization(offer.name)
                if (negotiation.forbidsWar(committer, target)) return true
            } else if (offer.type == TradeOfferType.PeaceProposal || (offer.type == TradeOfferType.Treaty && offer.name == Constants.peaceTreaty)) {
                val target = if (offer.type == TradeOfferType.PeaceProposal) committer.gameInfo.getCivilization(offer.name) else null
                if (target != null && negotiation.forbidsPeace(committer, target)) return true
            }
        }
        return false
    }
}
