package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: 型付きID群。すべてULID形式、生成後不変。
 * ID生成は `apap.domain.port.IdGenerator` 経由（直接UUID.randomUUID()等を呼ばない）。
 */
data class ProviderId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "ProviderId must be a valid ULID: $value" }
    }
}

data class ModelId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "ModelId must be a valid ULID: $value" }
    }
}

data class AliasId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "AliasId must be a valid ULID: $value" }
    }
}

data class TenantId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "TenantId must be a valid ULID: $value" }
    }
}

data class RequestId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "RequestId must be a valid ULID: $value" }
    }
}

data class ConversationId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "ConversationId must be a valid ULID: $value" }
    }
}

data class SessionId(
    val value: String,
) {
    init {
        require(Ulid.isValid(value)) { "SessionId must be a valid ULID: $value" }
    }
}
