package apap.adapter.anthropic

import org.slf4j.LoggerFactory

/**
 * このAdapterが**実APIに対して一度も検証されていない**ことの可視化。
 *
 * ## なぜコードに置くのか
 *
 * 構造的にはスタブと同じである——動いて見えるが、実挙動は未確認。本リポジトリは
 * 同じ構造のものに同じ扱いを与えてきた（`NoOpQueryEmbedder`は明示的な`optedIn=true`と
 * 構築時WARNを要求し、`ZeroCostEstimator`は`isStub`で自己申告する）。
 * README記載だけでは、時間が経つと「一通り検証した」という記憶に置き換わる。
 *
 * ## 何が未検証なのか
 *
 * 記録・再生に使うフィクスチャは公開API仕様からの**手書き**で、実通信の記録ではない。
 * したがって次のような、実APIに触れないと分からない事柄は確認できていない。
 *
 * - 実際のエラーボディの形と`retry-after`の有無
 * - ネットワーク越しのバックプレッシャ
 * - Usageの到着タイミング
 * - Structured Outputの`output_config.format`の内側の正確な形
 *
 * ## 解除する条件
 *
 * `docs/adapter-spi-findings.md` の「実測が必須になる条件」に到達したら、
 * `LiveProviderTest`で実測し、記録を差し替えたうえでこのクラスごと削除すること。
 * [LIVE_VERIFIED]をtrueにするだけでは不十分——WARNが消えて根拠が残らなくなる。
 */
object UnverifiedAgainstLiveApi {
    /**
     * 実APIでの検証が済んだらtrueにし、同時にこのクラスの削除を検討すること。
     * `UnverifiedAgainstLiveApiTest`が、falseである限り
     * findings文書に「実APIとの接触は未実施」の記述が残っていることを機械検証する。
     */
    const val LIVE_VERIFIED = false

    private val logger = LoggerFactory.getLogger(UnverifiedAgainstLiveApi::class.java)

    /**
     * Adapterの初期化時に1度だけ呼ぶ。`NoOpQueryEmbedder`と同じく、
     * **未検証のものが本番配線に載ったことを常に見えるようにする**ための警告。
     */
    fun warnIfUnverified() {
        if (LIVE_VERIFIED) return
        logger.warn(
            "adapter-anthropic has never been exercised against the live provider API. " +
                "Its replay fixtures are hand-authored from public documentation, so error bodies, " +
                "retry-after, backpressure and the structured-output wire format are unconfirmed. " +
                "See docs/adapter-spi-findings.md before sending real traffic.",
        )
    }
}
