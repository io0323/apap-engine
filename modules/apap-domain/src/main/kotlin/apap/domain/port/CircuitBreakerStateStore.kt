package apap.domain.port

import apap.domain.model.execution.CircuitBreakerState

/**
 * [CircuitBreakerStateRepository]（読取専用、Routingのハードフィルタ用）に書込を追加した口。
 * ADR-0001 / [CircuitBreakerStateRepository]のKDoc: Circuit Breaker状態の遷移
 * （tryAcquire/recordSuccess/recordFailure）はapap-executionの責務。Routingは引き続き
 * [CircuitBreakerStateRepository]（このインターフェースのスーパータイプ）越しに`find`だけを使う
 * ——同一バックエンドインスタンスをRouting/Executionの双方へ異なる型として注入できるようにする
 * ための拡張であり、Routing側の依存範囲を広げるものではない。
 */
interface CircuitBreakerStateStore : CircuitBreakerStateRepository {
    fun save(state: CircuitBreakerState)
}
