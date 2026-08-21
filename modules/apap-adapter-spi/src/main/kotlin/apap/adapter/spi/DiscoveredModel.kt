package apap.adapter.spi

import apap.domain.model.vo.CapabilityId

/**
 * 03_基本設計.md 3.3.2 `discoverModels(): DiscoveredModel[]` / 15_Provider追加手順.md 15.2。
 * `regions` はProvider側APIがコード体系を持たないことがあるため生の文字列で保持し、
 * 登録処理側（Infrastructure層）が `apap.domain.model.vo.RegionCodeTable` に対して検証し
 * [apap.domain.model.vo.Region] へ解決する。
 */
data class DiscoveredModel(
    val modelName: String,
    val version: String,
    val capabilities: Set<CapabilityId>,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val regions: Set<String>,
) {
    init {
        require(modelName.isNotBlank()) { "DiscoveredModel.modelName must not be blank" }
        require(version.isNotBlank()) { "DiscoveredModel.version must not be blank" }
        require(contextWindow > 0) { "contextWindow must be positive: $contextWindow" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive: $maxOutputTokens" }
    }
}
