package apap.gateway.routes

import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.gateway.auth.TokenVerifier
import apap.gateway.authenticate
import apap.gateway.config.GatewayConfig
import apap.gateway.error.ApiException
import apap.gateway.requireAdminScope
import apap.runtime.ApapEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * 13_API設計.md 13.1「管理系API（Admin権限）」。
 *
 * すべて[apap.runtime.ApapAdmin]（`ApapEngine.admin`）への委譲。ProviderManager/ModelManagerが
 * 持つ状態遷移の判断（09章の遷移表）はエンジン側にあり、Gatewayは呼び分けるだけ。
 *
 * `ApapAdmin`が公開していないオペレーション（quotas/budgets/analytics/audit/plugins/caches、
 * credentials:rotate、models:discovered、Provider PATCH）は
 * [apap.gateway.catalog.EndpointCatalog]に基づき理由付きの501を返す（ADR-0027）。
 */
fun Route.adminRoutes(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
) {
    providerAdminRoutes(engine, config, tokenVerifier)
    modelAdminRoutes(engine, config, tokenVerifier)
    policyAdminRoutes(engine, config, tokenVerifier)
    unavailableAdminRoutes()
}

private fun Route.providerAdminRoutes(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
) {
    post("/admin/v1/providers") {
        call.admin(tokenVerifier, config)
        val command = call.receive<apap.provider.RegisterProviderCommand>()
        // 13.5: 作成は201。
        call.respond(HttpStatusCode.Created, engine.admin.providers.register(command))
    }

    get("/admin/v1/providers") {
        call.admin(tokenVerifier, config)
        call.respond(engine.admin.providers.list())
    }

    get("/admin/v1/providers/{id}") {
        call.admin(tokenVerifier, config)
        val provider =
            engine.admin.providers.findById(ProviderId(call.pathParam("id")))
                ?: throw ApiException(ErrorCode.CAPABILITY_NOT_AVAILABLE, "Provider not found")
        call.respond(provider)
    }

    delete("/admin/v1/providers/{id}") {
        call.admin(tokenVerifier, config)
        engine.admin.providers.delete(ProviderId(call.pathParam("id")))
        // 13.5: 削除成功は204。
        call.respond(HttpStatusCode.NoContent)
    }

    // 13.1の`:enable | :drain | :disable | :validate`。パスにコロンを含む形（Google AIP風）。
    post("/admin/v1/providers/{id}:enable") {
        call.admin(tokenVerifier, config)
        call.respond(engine.admin.providers.enable(ProviderId(call.pathParam("id")), call.reason()))
    }

    post("/admin/v1/providers/{id}:drain") {
        call.admin(tokenVerifier, config)
        call.respond(engine.admin.providers.drain(ProviderId(call.pathParam("id")), call.reason()))
    }

    // 13.1の`:disable`は09章の遷移では DRAINING→DISABLED（排出完了）に相当する。
    post("/admin/v1/providers/{id}:disable") {
        call.admin(tokenVerifier, config)
        call.respond(engine.admin.providers.completeDraining(ProviderId(call.pathParam("id")), call.reason()))
    }

    post("/admin/v1/providers/{id}:validate") {
        call.admin(tokenVerifier, config)
        val providerId = ProviderId(call.pathParam("id"))
        engine.admin.providers.beginValidation(providerId)
        call.respond(engine.admin.providers.completeValidation(providerId))
    }
}

@Suppress("ThrowsCount")
private fun Route.modelAdminRoutes(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
) {
    post("/admin/v1/models") {
        call.admin(tokenVerifier, config)
        val command = call.receive<apap.provider.RegisterModelCommand>()
        call.respond(HttpStatusCode.Created, engine.admin.models.register(command))
    }

    /**
     * 13.1は「Model一覧」だが、`ModelRepository`に`findAll`が無く
     * `findByProvider`/`findByCapability`しか引けない。絞り込み条件を必須にすることで
     * 「一覧のつもりが実は一部しか返っていない」状態を避ける（EndpointCatalogにも明記）。
     */
    get("/admin/v1/models") {
        call.admin(tokenVerifier, config)
        val providerId = call.request.queryParameters["provider_id"]
        val capabilityId = call.request.queryParameters["capability_id"]
        val models =
            when {
                providerId != null -> engine.admin.models.findByProvider(ProviderId(providerId))
                capabilityId != null -> engine.admin.models.findByCapability(CapabilityId(capabilityId))
                else ->
                    throw ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "Either provider_id or capability_id query parameter is required " +
                            "(an unfiltered listing is not supported; see GET /v1/_endpoints)",
                    )
            }
        call.respond(models)
    }

    patch("/admin/v1/models/{id}") {
        call.admin(tokenVerifier, config)
        val body = call.receive<ModelPatchDto>()
        val status =
            runCatching { ModelStatus.valueOf(body.status.uppercase()) }
                .getOrElse { throw ApiException(ErrorCode.INVALID_REQUEST, "Unknown model status: ${body.status}") }
        call.respond(engine.admin.models.changeStatus(ModelId(call.pathParam("id")), status))
    }

    put("/admin/v1/aliases/{name}") {
        val caller = call.admin(tokenVerifier, config)
        val body = call.receive<AliasPutDto>()
        call.respond(
            engine.admin.models.assignAlias(
                tenantId = caller.tenantId,
                aliasId =
                    apap.domain.model.vo
                        .AliasId(body.aliasId),
                name = call.pathParam("name"),
                targets = body.targets,
            ),
        )
    }

    get("/admin/v1/aliases/{name}") {
        val caller = call.admin(tokenVerifier, config)
        val alias =
            engine.admin.models.findAlias(caller.tenantId, call.pathParam("name"))
                ?: throw ApiException(ErrorCode.ALIAS_NOT_FOUND, "Alias not found")
        call.respond(alias)
    }
}

private fun Route.policyAdminRoutes(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
) {
    post("/admin/v1/policies") {
        call.admin(tokenVerifier, config)
        val policy = call.receive<apap.domain.model.routing.RoutingPolicy>()
        engine.admin.policies.save(policy)
        call.respond(HttpStatusCode.Created, policy)
    }

    put("/admin/v1/policies") {
        call.admin(tokenVerifier, config)
        val policy = call.receive<apap.domain.model.routing.RoutingPolicy>()
        engine.admin.policies.save(policy)
        call.respond(policy)
    }

    get("/admin/v1/policies") {
        val caller = call.admin(tokenVerifier, config)
        val workflowId = call.request.queryParameters["workflow_id"]
        call.respond(engine.admin.policies.findEffective(caller.tenantId, workflowId))
    }

    get("/admin/v1/health/providers") {
        call.admin(tokenVerifier, config)
        val result = engine.health.providerHealth()
        call.respond(mapOf("state" to result.state.name, "details" to result.details))
    }
}

/** 13.1にあるが本ビルドでは提供していない管理系（ADR-0027）。 */
private fun Route.unavailableAdminRoutes() {
    notImplementedRoutes(
        listOf(
            "/admin/v1/providers/{id}/credentials:rotate",
            "/admin/v1/models:discovered",
            "/admin/v1/quotas",
            "/admin/v1/budgets",
            "/admin/v1/analytics",
            "/admin/v1/audit",
            "/admin/v1/plugins",
            "/admin/v1/caches:invalidate",
        ),
    )
    // Provider PATCHはManagerに汎用更新が無いため未提供（他と同じくカタログ由来）。
    registerNotImplementedRoute(
        "PATCH",
        "/admin/v1/providers/{id}",
        apap.gateway.catalog.EndpointCatalog
            .find("PATCH", "/admin/v1/providers/{id}")
            ?.unavailableReason
            .orEmpty(),
    )
}

/** Model status変更（13.1「PATCH /admin/v1/models/{id}（status含む）」）。 */
data class ModelPatchDto(
    val status: String,
)

/** Alias付替（13.1「PUT /admin/v1/aliases/{name}（Canary weight）」）。 */
data class AliasPutDto(
    val aliasId: String,
    val targets: List<apap.domain.model.modelcatalog.AliasTarget>,
)

/** 認証 + Adminスコープ検査をまとめて行う。 */
private suspend fun ApplicationCall.admin(
    tokenVerifier: TokenVerifier,
    config: GatewayConfig,
): apap.gateway.auth.VerifiedCaller {
    val caller = authenticate(tokenVerifier)
    caller.requireAdminScope(config.auth.adminScope)
    withRequestId()
    return caller
}

private fun ApplicationCall.pathParam(name: String): String =
    parameters[name]
        ?: throw ApiException(ErrorCode.INVALID_REQUEST, "Path parameter '$name' is required")

/** 状態操作APIの理由（監査に残る）。未指定でも操作は許すが、空文字は残さない。 */
private fun ApplicationCall.reason(): String = request.queryParameters["reason"] ?: "changed via admin API"
