package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: 能力識別子。形式は `[a-z_]{3,40}`。
 * 「Capability Registry登録済」であることの検証はI/Oを伴うため、
 * ここでは形式検証のみを行い、登録済チェックはRepositoryを持つ層（Application/Domain Service）が担う。
 */
data class CapabilityId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "CapabilityId must match [a-z_]{3,40}: $value" }
    }

    companion object {
        private val PATTERN = Regex("^[a-z_]{3,40}$")
    }
}
