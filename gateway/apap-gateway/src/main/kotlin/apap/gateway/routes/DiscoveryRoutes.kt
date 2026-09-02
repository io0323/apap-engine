package apap.gateway.routes

import apap.gateway.REQUEST_ID_HEADER
import apap.gateway.auth.TokenVerifier
import apap.gateway.authenticate
import apap.gateway.catalog.EndpointCatalog
import apap.gateway.dto.CapabilityDto
import apap.gateway.error.ApiException
import apap.runtime.ApapEngine
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * 13_API設計.md 13.1「Discovery / Context系API」。
 *
 * `/v1/capabilities`はテナント権限適用済みの一覧（`ApapEngine.capabilities(tenantId)`が
 * PolicyRepositoryを通して絞り込む）。Gatewayは絞り込みに関与しない。
 */
fun Route.discoveryRoutes(
    engine: ApapEngine,
    tokenVerifier: TokenVerifier,
) {
    get("/v1/capabilities") {
        val caller = call.authenticate(tokenVerifier)
        call.withRequestId()
        call.respond(engine.capabilities(caller.tenantId).map { it.toDto() })
    }

    get("/v1/capabilities/{capability_id}") {
        val caller = call.authenticate(tokenVerifier)
        val capabilityId =
            call.parameters["capability_id"]
                ?: throw ApiException(
                    apap.domain.model.vo.ErrorCode.INVALID_REQUEST,
                    "capability_id path parameter is required",
                )
        val descriptor =
            engine.capabilities(caller.tenantId).firstOrNull { it.capabilityId.value == capabilityId }
                ?: throw ApiException(
                    apap.domain.model.vo.ErrorCode.CAPABILITY_NOT_AVAILABLE,
                    "Capability '$capabilityId' is not available for this tenant",
                )
        call.withRequestId()
        call.respond(descriptor.toDto())
    }

    /**
     * ADR-0027: 13.1の全エンドポイントについて、本ビルドでの提供状況を機械可読に公開する。
     * 「提供していない」ことを利用者が事前に知れるようにするための口であり、
     * 501を返してから初めて分かる、という状態を避ける。
     */
    get("/v1/_endpoints") {
        call.withRequestId()
        call.respond(
            EndpointCatalog.entries.map { spec ->
                mapOf(
                    "method" to spec.method,
                    "path" to spec.path,
                    "summary" to spec.summary,
                    "status" to spec.status.name,
                    "unavailable_reason" to spec.unavailableReason,
                )
            },
        )
    }

    // 13.1にあるが本ビルドでは提供していないDiscovery/Context系（ADR-0027）。
    notImplementedRoutes(listOf("/v1/aliases", "/v1/sessions", "/v1/conversations"))
}

private fun apap.api.CapabilityDescriptor.toDto() =
    CapabilityDto(
        capabilityId = capabilityId.value,
        name = name,
        streamable = streamable,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
    )

internal fun ApplicationCall.withRequestId() {
    response.header(REQUEST_ID_HEADER, callId ?: "unknown")
}
