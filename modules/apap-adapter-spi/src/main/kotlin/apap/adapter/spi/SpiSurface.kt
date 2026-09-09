package apap.adapter.spi

/**
 * ADR-0016: [DomainAliases.kt][apap.adapter.spi]が再エクスポートするドメイン型の単一管理箇所。
 *
 * この一覧は [DomainAliases.kt] のtypealias宣言（名前・再エクスポート先の完全修飾名）と
 * 完全一致することを `SpiSurfaceTest`（Konsistアーキテクチャテスト）で機械検証する。
 * SPI公開面（ここで管理する型の集合）へ型を追加・削除する際は、このオブジェクトも必ず同時に
 * 更新すること——さもなくば`SpiSurfaceTest`が失敗する。これはSPI公開面の変更を必ずレビュー対象の
 * diffへ現れさせるための意図的な仕掛けである（ADR-0016 決定2）。
 *
 * SPI公開面に含まれる型への破壊的変更（フィールド削除・型変更・制約強化）は、
 * `apap-adapter-spi`自体のメジャーバージョン更新を要する（ADR-0016 決定3、CLAUDE.md「実装規約」）。
 */
object SpiSurface {
    /**
     * `apap-adapter-spi` 自身のバージョン（ADR-0016のsemver規約）。**単一の管理箇所**とし、
     * Adapterの`spiVersion()`と`plugin.yaml`の`spi_version`レンジはこれを基準に決める。
     *
     * ## 変更履歴
     *
     * | 版 | 内容 | 判定 |
     * |---|---|---|
     * | 1.0.0 | 初版 | — |
     * | 1.1.0 | P16のSPI拡張 | **マイナー**（追加のみ、既定値あり、順序末尾） |
     * | 2.0.0 | `capabilityConstraints`の削除（ADR-0042） | **メジャー**（メソッド削除） |
     *
     * 1.1.0で追加したもの: `CapabilityConstraints.supportedInputModalities`（ADR-0039）、
     * `AdapterConfig.credentialRefs`（ADR-0038）、`AdapterRequest.modelMaxOutputTokens`（ADR-0040）、
     * `AdapterChunk.finishReason`（13.3の`message_end`、ADR-0028をSupersede）。
     * いずれも省略可能フィールドの末尾追加のためマイナーに留めた。
     *
     * 2.0.0で削除したもの: `ProviderAdapter.capabilityConstraints`、`CapabilityConstraints`型、
     * `Modality`のtypealias（ADR-0042）。**メソッドの削除は既存Adapterのコンパイルを壊す**ため
     * メジャー更新に当たる。Adapter側の対応は`override`を1つ消し、`plugin.yaml`の
     * `spi_version`レンジを`>=2.0 <3.0`へ更新すること。
     */
    val version: SemVer = SemVer(2, 0, 0)

    /** typealias名 → 再エクスポート先の完全修飾クラス名。 */
    val exposedDomainTypes: Map<String, String> =
        mapOf(
            "CapabilityId" to "apap.domain.model.vo.CapabilityId",
            "ContentPart" to "apap.domain.model.vo.ContentPart",
            "InputMessage" to "apap.domain.model.execution.InputMessage",
            "TurnRole" to "apap.domain.model.conversation.TurnRole",
            "TextContentPart" to "apap.domain.model.vo.ContentPart.Text",
            "ImageContentPart" to "apap.domain.model.vo.ContentPart.Image",
            "AudioContentPart" to "apap.domain.model.vo.ContentPart.Audio",
            "VideoContentPart" to "apap.domain.model.vo.ContentPart.Video",
            "JsonContentPart" to "apap.domain.model.vo.ContentPart.Json",
            "CredentialRef" to "apap.domain.model.vo.CredentialRef",
            "CredentialState" to "apap.domain.model.vo.CredentialState",
            "AdapterErrorCategory" to "apap.domain.model.vo.AdapterErrorCategory",
            "FinishReason" to "apap.domain.model.vo.FinishReason",
            "Period" to "apap.domain.model.vo.Period",
            "SemVer" to "apap.domain.model.vo.SemVer",
            "TokenCount" to "apap.domain.model.vo.TokenCount",
            "Usage" to "apap.domain.model.vo.Usage",
            "ProviderHealthStatus" to "apap.domain.model.provider.ProviderHealthStatus",
        )

    /**
     * [ProviderAdapter]の各メソッドと、**本番コード側の呼び出し元**（クローズドセット）。
     *
     * ## なぜこの表が要るのか
     *
     * `capabilityConstraints`は2フェーズにわたり「実装されているが本番の誰も読まない」まま残り、
     * Adapter作者には意味のあるメソッドに見えていた（ADR-0042で削除）。同じ形の欠落は
     * `MetricsEngine`・`AuditEngine`・`CapabilityRegistry`でも起きている。**宣言だけを増やせない**
     * ようにするため、メソッド集合と消費者の対応をここで管理し、`ProviderAdapterSurfaceTest`が
     * 機械検証する。SPIへメソッドを足すときは、この表も更新しなければビルドが落ちる。
     *
     * 値が[NO_CONSUMER_PREFIX]で始まるものは、**現時点で本番の呼び出し元が無い**ことを
     * 明示的に宣言したものである（理由を続けて書く）。黙って増やさないための逃げ道であって、
     * 推奨される状態ではない。
     */
    val adapterMethodConsumers: Map<String, String> =
        mapOf(
            "initialize" to "apap.provider.ProviderAdapterProvisioner",
            "shutdown" to "apap.provider.ProviderAdapterProvisioner",
            "spiVersion" to "apap.plugin.PluginManager (ロード時のマニフェスト/コード突き合わせ)",
            "supportedCapabilities" to "apap.provider.ProviderManager (15.1 Step5の突合)",
            "authenticate" to "apap.execution.attempt.AttemptExecutor / StreamingRequestExecutor",
            "validateCredential" to "apap.provider.ProviderManager",
            "execute" to "apap.execution.attempt.AttemptExecutor",
            "executeStream" to "apap.execution.streaming.StreamingRequestExecutor",
            "translateTools" to
                "$NO_CONSUMER_PREFIX コアは`AdapterRequest.tools`をSPIの共通形式のまま渡し、" +
                "Provider形式への変換はAdapter内部で完結している（findings §4）",
            "discoverModels" to
                "$NO_CONSUMER_PREFIX 検出結果を承認してModel登録する経路（15.1 Step6）が未実装" +
                "（apap.gateway.catalog.EndpointCatalogに未提供APIとして記録済み）",
            "healthCheck" to "apap.provider.ProviderHealthCheckTask / ProviderManager",
            "fetchUsage" to "$NO_CONSUMER_PREFIX Provider側集計APIを取り込むユースケースが未実装（findings §4.2）",
            "fetchCost" to "$NO_CONSUMER_PREFIX 同上（findings §4.2）",
            "estimateTokens" to "apap.execution.estimation.TokenEstimator",
        )

    /** [adapterMethodConsumers]で「本番の消費者が無い」ことを宣言する接頭辞。 */
    const val NO_CONSUMER_PREFIX: String = "NO PRODUCTION CONSUMER:"
}
