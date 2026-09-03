package apap.runtime

import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.domain.event.DomainEvent
import apap.domain.event.ProviderRegistered
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.ProviderId
import apap.domain.port.ProviderRepository
import org.slf4j.LoggerFactory

/**
 * 登録済みProviderの[RateLimits]を[RateLimiter]のバケット設定へ反映する（FR-EXE-003 / P11-F10）。
 *
 * ## 解決する問題
 *
 * `RateLimiter.configure(scope, ...)` は本番配線のどこからも呼ばれていなかった。
 * Providerを`rpm=600`で登録しても、レート制限は`RateLimiterConfig`の既定
 * （容量60・毎秒1トークン補充）のまま動き、**バースト60件のあとは毎秒1リクエスト**に
 * 絞られていた。P11のベンチマークで出荷時スループットが 4.7 req/s だった原因がこれである。
 * 「Providerのrpmを保持している」ことと「rpmが効いている」ことは別で、前者しか無かった。
 *
 * ## 反映のタイミング
 *
 * 1. **エンジン構築時**: リポジトリにある全Providerへ適用する。
 *    永続化構成（`apap-infrastructure-jdbc`）ではプロセス再起動後もProviderは残るため、
 *    イベント購読だけでは再起動後に設定が失われる。
 * 2. **登録時**: `ProviderRegistered`を購読して適用する（`rateLimits`はADR-0026の
 *    payload拡張でイベントに含まれている）。
 *
 * `configure()`はバケットを満タンにリセットするため**リクエストごとには呼べない**
 * （毎回呼ぶとレート制限が事実上無効になる）。上記2点は「Providerの設定が変わった時」
 * だけに限定されており、この性質と両立する。
 *
 * ## rpm からトークンバケットへの換算
 *
 * `rpm`（1分あたり許容リクエスト数）を、容量`rpm`・毎秒`rpm/60`補充のバケットとする。
 * 容量をrpm相当にすることで「1分ぶんのバースト」を許容し、定常レートはrpmに一致する。
 * `tpm`（トークン数）と`concurrent`（同時実行数）は現在のトークンバケットでは表現できない
 * ため未反映（`docs/verification-report.md`に既知の未対応として記載）。
 */
internal class ProviderRateLimitConfigurer(
    private val rateLimiter: RateLimiter,
) {
    /** 起動時に既存Providerへ適用する。 */
    fun applyExisting(providerRepository: ProviderRepository) {
        providerRepository.findAll().forEach { apply(it.providerId, it.rateLimits) }
    }

    /** `ProviderRegistered`を受けて適用する。他のイベントは無視する。 */
    fun onEvent(event: DomainEvent) {
        if (event is ProviderRegistered) apply(event.providerId, event.rateLimits)
    }

    private fun apply(
        providerId: ProviderId,
        limits: RateLimits,
    ) {
        val refillPerSecond = limits.rpm.toDouble() / SECONDS_PER_MINUTE
        rateLimiter.configure(RateLimitScope.ProviderScope(providerId), limits.rpm, refillPerSecond)
        logger.debug(
            "configured provider rate limit providerId={} rpm={} refillPerSecond={}",
            providerId.value,
            limits.rpm,
            refillPerSecond,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ProviderRateLimitConfigurer::class.java)
        const val SECONDS_PER_MINUTE = 60.0
    }
}
