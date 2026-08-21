package apap.execution.adapter.out

import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.vo.CbKey
import apap.domain.port.CircuitBreakerStateStore
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0001: Circuit Breaker状態はRDBMSに置かない。単一プロセス埋込利用（`apap-runtime` を
 * prompt-engine が依存する既定用途）ではin-memory実装で十分であり、これが本番既定実装となる
 * （分散KVS実装はマルチノード運用時のみ必要な将来フェーズ、P8想定）。
 *
 * `apap-testkit` の `InMemoryCircuitBreakerStateRepository`（読取専用のテスト用フェイク）とは別物。
 * 本クラスは書込可能な[CircuitBreakerStateStore]の本番デフォルト実装であり、`apap-runtime`の
 * コンポジションルートがRouting（[apap.domain.port.CircuitBreakerStateRepository]として）と
 * Execution（本インターフェースとして）の双方へ同一インスタンスを配布する。
 */
class InMemoryCircuitBreakerStateStore : CircuitBreakerStateStore {
    private val states = ConcurrentHashMap<CbKey, CircuitBreakerState>()

    override fun find(key: CbKey): CircuitBreakerState? = states[key]

    override fun save(state: CircuitBreakerState) {
        states[state.cbKey] = state
    }
}
