package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.CommitmentSnapshotCodec

/**
 * Serializes and restores chat transcripts and manipulation-tracker history per leader pair into a single
 * versioned string (`GameInfo.chatData`), so conversations, brown-nosing memory, and hostility strikes
 * survive turn clones and save/load.
 */
data class SavedLeaderChat(
    val key: String,
    val messages: List<ChatSession.Message>,
    val recentFlatteries: List<Boolean> = emptyList(),
    val recentFlatteryAsks: List<Boolean> = emptyList(),
    val hostileStrikes: Int = 0
)

object ChatSnapshotCodec {
    private const val VERSION = "CHAT_V1"
    private const val MAX_SAVED_MESSAGES_PER_PAIR = 24

    fun encode(chats: Collection<SavedLeaderChat>): String = buildString {
        if (chats.isEmpty()) return ""
        appendLine(VERSION)
        for (chat in chats) {
            val escKey = CommitmentSnapshotCodec.esc(chat.key)
            val flatStr = chat.recentFlatteries.joinToString(",") { if (it) "1" else "0" }
            val askStr = chat.recentFlatteryAsks.joinToString(",") { if (it) "1" else "0" }
            appendLine("T|$escKey|$flatStr|$askStr|${chat.hostileStrikes}")
            for (msg in chat.messages.takeLast(MAX_SAVED_MESSAGES_PER_PAIR)) {
                val role = if (msg.fromPlayer) "P" else "L"
                appendLine("M|$escKey|$role|${CommitmentSnapshotCodec.esc(msg.text)}")
            }
        }
    }

    fun decode(text: String): List<SavedLeaderChat> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return emptyList()

        val messagesByKey = LinkedHashMap<String, MutableList<ChatSession.Message>>()
        val flatteriesByKey = HashMap<String, List<Boolean>>()
        val asksByKey = HashMap<String, List<Boolean>>()
        val strikesByKey = HashMap<String, Int>()

        for (line in lines.drop(1)) {
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "T" -> if (parts.size >= 4) {
                    val key = CommitmentSnapshotCodec.unesc(parts[1])
                    if (!messagesByKey.containsKey(key)) messagesByKey[key] = ArrayList()
                    flatteriesByKey[key] = parseBits(parts[2])
                    asksByKey[key] = parseBits(parts[3])
                    strikesByKey[key] = parts.getOrNull(4)?.toIntOrNull() ?: 0
                }
                "M" -> if (parts.size >= 4) {
                    val key = CommitmentSnapshotCodec.unesc(parts[1])
                    val fromPlayer = parts[2] == "P"
                    val msgText = CommitmentSnapshotCodec.unesc(parts[3])
                    messagesByKey.getOrPut(key) { ArrayList() }.add(ChatSession.Message(fromPlayer, msgText))
                }
            }
        }
        return messagesByKey.map { (key, msgs) ->
            SavedLeaderChat(
                key = key,
                messages = msgs,
                recentFlatteries = flatteriesByKey[key].orEmpty(),
                recentFlatteryAsks = asksByKey[key].orEmpty(),
                hostileStrikes = strikesByKey[key] ?: 0
            )
        }
    }

    private fun parseBits(s: String): List<Boolean> =
        if (s.isBlank()) emptyList() else s.split(',').map { it == "1" }
}
