package agents_engine.model

/**
 * Conversation-history caching mode.
 *
 * The agentic loop resends the entire conversation prefix on every turn.
 * That prefix is mostly byte-identical (older turns never change) but grows
 * by one assistant + one user message each round. [Rolling] inserts a fresh
 * cache breakpoint at the end of every turn so the growing prefix continues
 * to hit. Default is [None] — rolling has a per-vendor write cost
 * (Anthropic charges 25% more for the cached-write tokens) and only pays
 * back on long loops; opt-in keeps short loops from paying the write tax
 * for cache hits they would never collect.
 */
enum class CacheConversation { None, Rolling }
