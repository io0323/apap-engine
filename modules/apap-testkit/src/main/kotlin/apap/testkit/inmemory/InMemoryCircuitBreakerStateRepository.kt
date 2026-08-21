package apap.testkit.inmemory

import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.vo.CbKey
import apap.domain.port.CircuitBreakerStateRepository

/** テスト側で`put`を使い任意の[CircuitBreakerState]を仕込める。未設定なら`find`はnullを返す（既定CLOSED相当）。 */
class InMemoryCircuitBreakerStateRepository : CircuitBreakerStateRepository {
    private val states = mutableMapOf<CbKey, CircuitBreakerState>()

    override fun find(key: CbKey): CircuitBreakerState? = states[key]

    fun put(state: CircuitBreakerState) {
        states[state.cbKey] = state
    }
}
