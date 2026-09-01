package apap.api

import apap.domain.model.vo.CapabilityId

/**
 * [apap.runtime.ApapEngine.capabilities]が返す、あるテナントに対して現在有効なCapabilityの記述。
 * `apap.domain.model.capability.CapabilityDefinition`（Capability Registry全体の管理用表現）から、
 * 利用側が呼出可否の判断に必要な部分だけを取り出した公開版。
 */
data class CapabilityDescriptor(
    val capabilityId: CapabilityId,
    val name: String,
    val streamable: Boolean,
    val inputSchema: String,
    val outputSchema: String,
)
