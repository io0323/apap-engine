package apap.domain.model.vo

/** 02_システム仕様.md 2.19: apap_tokens_total{direction(in/out)}。 */
enum class TokenDirection { IN, OUT }

/** 02_システム仕様.md 2.19: apap_cache_events_total{type(hit/miss/store)}。 */
enum class CacheEventType { HIT, MISS, STORE }

/** 02_システム仕様.md 2.19: apap_rate_limit_events_total{action(wait/reject)}。 */
enum class RateLimitAction { WAIT, REJECT }
