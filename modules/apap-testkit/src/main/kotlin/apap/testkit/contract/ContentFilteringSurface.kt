package apap.testkit.contract

import apap.adapter.spi.AdapterRequest

/**
 * コンテンツ拒否がどちらの経路で表面化するかの申告（ADR-0037）。
 *
 * 設計書は同じ概念を2箇所に持つ。
 * - 02_システム仕様.md 2.9: `FinishReason` 6値に `content_filtered`（**応答側**）
 * - 02_システム仕様.md 2.11: エラー分類に `CONTENT_FILTERED`（**例外側**）
 *
 * 両者の関係は定義されておらず、Providerによってどちらで来るかが違う。
 * P15で実Providerを1つ実装したところ、そのProviderは拒否を**HTTP 200の正常応答**として返し、
 * 例外側の分類を作れなかったため Contract Test の該当項目がスキップになっていた
 * ——「再現できないから飛ばす」を許すと、緑のまま検証漏れが残る。
 *
 * そこで**Adapterに宣言させ、宣言どおりに検証する**。再現手段が無い場合も
 * [NotReachable]で理由を明示させ、黙って飛ばせないようにする。
 */
sealed interface ContentFilteringSurface {
    /** 例外（`AdapterErrorCategory.CONTENT_FILTERED`）として届く。2.11の想定。 */
    data class AsException(
        val request: AdapterRequest,
    ) : ContentFilteringSurface

    /** 正常応答（`FinishReason.CONTENT_FILTERED`）として届く。2.9の想定。 */
    data class AsFinishReason(
        val request: AdapterRequest,
    ) : ContentFilteringSurface

    /**
     * このAdapterでは再現手段が無い。[reason]は必須で、空文字は検証が落ちる。
     * 「書けない除外は漏れと区別できない」——リポジトリ全体で採っている方針と同じ。
     */
    data class NotReachable(
        val reason: String,
    ) : ContentFilteringSurface
}
