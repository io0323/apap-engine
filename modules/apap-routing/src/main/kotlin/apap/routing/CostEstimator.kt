package apap.routing

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import org.slf4j.LoggerFactory

/**
 * 02_システム仕様.md 2.5.2 S_cost算出に使う候補の推定コスト。本格的な単価管理・トークン推定
 * （PriceBook、Tokenizer）はapap-cost/Prompt Engineの責務であり本モジュールの対象外のため、
 * 3.8 Strategy一覧の思想（差替可能なStrategy）にならい、既定実装として[ZeroCostEstimator]を
 * 用意する。
 *
 * [isStub]は実装がこのスタブ性質を自己申告するためのフラグ。ZeroCostEstimatorが常にゼロを返す
 * ことでS_costがスコアへ差を与えなくなり、`optimize_for=cost`時は合成スコアの55%
 * （2.5.2の重み表）が定数になる——ルーティングは動いて見えテストも通るが、コスト最適化という
 * 性質自体は満たしていない。この種の「動いているように見えるが性質を満たさない」状態
 * （空スコープの沈黙、スナップショットの読み替え、typealiasの見かけの分離と同型の失敗モード）を
 * 繰り返さないため、スタブは黙って使われることを許さない: [RoutingDecision.costEstimationStubbed]
 * へ伝播し監査サマリに現れる（構造的に検知可能）ほか、ZeroCostEstimator自体も構築時にWARNログを
 * 出す（運用時に検知可能）。将来apap-costが実装された際は、この口へ実コスト算出実装を差し替える。
 */
interface CostEstimator {
    val isStub: Boolean get() = false

    /**
     * ADR-0021: 戻り値`null`は「有効なPriceEntryが存在しない」ことを表す。呼び出し元
     * （[CandidateFactory]）はその場合Candidate自体を組み立てず除外する（ペナルティではなく
     * ハードフィルタ相当の除外、単価未登録Modelを選択させないため）。
     */
    fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
    ): Money?
}

class ZeroCostEstimator(
    private val currency: String = DEFAULT_CURRENCY,
) : CostEstimator {
    override val isStub: Boolean = true

    init {
        logger.warn(
            "ZeroCostEstimator is wired in — S_cost is a constant for every candidate " +
                "until a real CostEstimator (apap-cost) replaces it. " +
                "optimize_for=cost routing decisions will not actually optimize for cost.",
        )
    }

    override fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
    ): Money? = Money.zero(currency)

    companion object {
        private const val DEFAULT_CURRENCY = "USD"
        private val logger = LoggerFactory.getLogger(ZeroCostEstimator::class.java)
    }
}
