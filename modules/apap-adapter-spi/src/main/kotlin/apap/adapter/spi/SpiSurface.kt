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
     *
     * 1.1.0で追加したもの: `CapabilityConstraints.supportedInputModalities`（ADR-0039）、
     * `AdapterConfig.credentialRefs`（ADR-0038）、`AdapterRequest.modelMaxOutputTokens`（ADR-0040）、
     * `AdapterChunk.finishReason`（13.3の`message_end`、ADR-0028をSupersede）。
     *
     * いずれも**省略可能フィールドの末尾追加**であり、フィールド削除・型変更・制約強化は
     * 行っていないためメジャー更新には当たらない。既存Adapterは無改修でコンパイル・動作する
     * （`AdapterContractTest`に抽象メソッドを1つ足したため、**契約テストの実装だけは**更新が要る。
     * これは`apap-testkit`側の変更でSPI公開面ではない）。
     */
    val version: SemVer = SemVer(1, 1, 0)

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
            "Modality" to "apap.domain.model.vo.Modality",
            "Period" to "apap.domain.model.vo.Period",
            "SemVer" to "apap.domain.model.vo.SemVer",
            "TokenCount" to "apap.domain.model.vo.TokenCount",
            "Usage" to "apap.domain.model.vo.Usage",
            "ProviderHealthStatus" to "apap.domain.model.provider.ProviderHealthStatus",
        )
}
