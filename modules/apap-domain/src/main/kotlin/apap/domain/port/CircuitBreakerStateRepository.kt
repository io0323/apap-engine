package apap.domain.port

import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.vo.CbKey

/**
 * 02_システム仕様.md 2.5.2 ハードフィルタ(b): Circuit BreakerがOPENでない候補のみ残す。
 * CB状態そのものの遷移（tryAcquire/recordSuccess/recordFailure）はapap-executionの責務であり
 * 本Portの範囲外。ここではRouting候補組立時に現在の状態を読むための最小限の読み取り口のみを定義する。
 */
interface CircuitBreakerStateRepository {
    fun find(key: CbKey): CircuitBreakerState?
}
