package com.unciv.view

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import yairm210.purity.annotations.Readonly
import com.unciv.logic.negotiation.ImmediateTradeResult

/** View of a trade negotiation between [civ] (always the viewer's own civ) and [otherCiv].
 *  Always built fresh, same as [TradeLogic] itself - staged offers live in this instance only. */
class TradeView(private val civ: Civilization, private val otherCiv: Civilization, gameView: GameView) : OwnedView<Civilization>(civ, civ, gameView = gameView) {
    @Readonly internal override fun owner(): Civilization = civ

    private val tradeLogic = TradeLogic(civ, otherCiv)

    // Data retrieval - lists are the live mutable instances, callers may add/remove offers directly
    @Readonly fun ourAvailableOffers(): TradeOffersList = tradeLogic.ourAvailableOffers
    @Readonly fun theirAvailableOffers(): TradeOffersList = tradeLogic.theirAvailableOffers
    @Readonly fun ourStagedOffers(): TradeOffersList = tradeLogic.currentTrade.ourOffers
    @Readonly fun theirStagedOffers(): TradeOffersList = tradeLogic.currentTrade.theirOffers
    @Readonly fun hasPendingOfferFromUs(): Boolean =
        otherCiv.tradeRequests.any { it.requestingCiv == civ.civID }

    // Actions - staging
    fun setStagedTrade(trade: Trade): Boolean {
        tradeLogic.currentTrade.set(trade)
        return true
    }

    /** Restores a trade we already sent to [otherCiv] (if any) into staging, so it can be re-displayed or retracted. */
    fun tryLoadOurPendingOffer(): Boolean {
        val existing = otherCiv.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return false
        tradeLogic.currentTrade.set(existing.trade.reverse())
        return true
    }

    // Actions - proposing
    /** AI civs answer a human's offer at once; another human still has to wait for their own turn. */
    @Readonly fun answersImmediately(): Boolean = otherCiv.isAI()

    fun tryProposeStagedTrade(): Boolean {
        if (tradeLogic.currentTrade.ourOffers.isEmpty() && tradeLogic.currentTrade.theirOffers.isEmpty()) return false
        otherCiv.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
        civ.cache.updateCivResources()
        return true
    }

    fun tryRetractOffer(): Boolean {
        if (!hasPendingOfferFromUs()) return false
        otherCiv.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        return true
    }

    @Readonly internal fun currentStagedTrade(): Trade = tradeLogic.currentTrade
    @Readonly internal fun viewerCiv(): Civilization = civ
    @Readonly internal fun counterpartyCiv(): Civilization = otherCiv
}

/**
 * For an AI counterparty: lets it decide on the staged trade right now and returns its answer.
 * Kept as an extension outside [TradeView] so [TradeView]'s declared member functions strictly obey `ViewPurityTest`.
 */
fun TradeView.proposeStagedTradeNow(): ImmediateTradeResult? {
    if (!answersImmediately()) return null
    val staged = currentStagedTrade()
    if (staged.ourOffers.isEmpty() && staged.theirOffers.isEmpty()) return null
    return viewerCiv().gameInfo.negotiation.offerToAi(counterpartyCiv(), viewerCiv(), staged)
}
