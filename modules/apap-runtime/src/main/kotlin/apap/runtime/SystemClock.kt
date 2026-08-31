package apap.runtime

import apap.domain.port.Clock
import java.time.Instant

/**
 * [ApapEngineBuilder]の既定[Clock]実装（CLAUDE.md実装規約: 直接の`Instant.now()`呼び出しは
 * このPortの実装クラス自身にのみ許可される、`ClockAndIdGeneratorDirectCallTest`のallowlist参照）。
 * 埋込ホストが独自の[Clock]を注入したい場合は[ApapEngineBuilder.clock]で差し替える。
 */
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
