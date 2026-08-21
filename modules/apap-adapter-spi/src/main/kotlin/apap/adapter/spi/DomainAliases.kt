package apap.adapter.spi

/**
 * 01_CLAUDE.md 不変条件2: adapters配下は`apap-adapter-spi`のみに依存する。この契約（[ProviderAdapter]
 * とその周辺の値クラス）は3.3.1/3.3.2の値をそのまま[apap.domain]から再利用しているため、Adapter実装が
 * これらの値を構築・参照するには型を参照できる必要がある。`apap.domain.model.vo.CapabilityId`等へ
 * 直接importさせるとAdapterDependencyRuleTest（Konsistでimport prefixを機械検証）に抵触するため、
 * Adapter実装からは常に`apap.adapter.spi`名前空間のtypealias経由で参照させる
 * （実行時コストゼロ、実体は同一クラス）。
 */
typealias CapabilityId = apap.domain.model.vo.CapabilityId

typealias ContentPart = apap.domain.model.vo.ContentPart

// Kotlinのtypealiasは、ネストしたクラスをエイリアス経由の修飾アクセス（`ContentPart.Text(...)`）で
// 構築することをこのビルド構成では解決できなかったため、コンストラクタ用に個別のtypealiasを用意する。
typealias TextContentPart = apap.domain.model.vo.ContentPart.Text

typealias ImageContentPart = apap.domain.model.vo.ContentPart.Image

typealias AudioContentPart = apap.domain.model.vo.ContentPart.Audio

typealias VideoContentPart = apap.domain.model.vo.ContentPart.Video

typealias JsonContentPart = apap.domain.model.vo.ContentPart.Json

typealias CredentialRef = apap.domain.model.vo.CredentialRef

typealias CredentialState = apap.domain.model.vo.CredentialState

typealias AdapterErrorCategory = apap.domain.model.vo.AdapterErrorCategory

typealias FinishReason = apap.domain.model.vo.FinishReason

typealias Period = apap.domain.model.vo.Period

typealias SemVer = apap.domain.model.vo.SemVer

typealias TokenCount = apap.domain.model.vo.TokenCount

typealias Usage = apap.domain.model.vo.Usage

typealias ProviderHealthStatus = apap.domain.model.provider.ProviderHealthStatus
