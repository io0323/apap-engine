package apap.routing

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.RoutingConstraints
import apap.domain.model.vo.RoutingPreferences
import apap.domain.model.vo.TenantId

/** 02_システム仕様.md 2.5.1: RoutingRequest。apap-domainには構成VO（Constraints/Preferences）のみが
 * あり、複合DTOはapap-routingが公開する入口として定義する。 */
data class RoutingRequest(
    val capabilityId: CapabilityId,
    val tenantId: TenantId,
    val modelAlias: String? = null,
    val constraints: RoutingConstraints = RoutingConstraints(),
    val preferences: RoutingPreferences = RoutingPreferences(),
    val conversationId: ConversationId? = null,
)
