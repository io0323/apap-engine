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
    /** typealias名 → 再エクスポート先の完全修飾クラス名。 */
    val exposedDomainTypes: Map<String, String> =
        mapOf(
            "CapabilityId" to "apap.domain.model.vo.CapabilityId",
            "ContentPart" to "apap.domain.model.vo.ContentPart",
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
}
