package apap.gateway.catalog

/**
 * 13_API設計.md 13.1に列挙された全エンドポイントと、本ビルドでの提供状況の対応表。
 *
 * 存在理由（ADR-0027）: 13.1のエンドポイントのうち、対応するユースケースが
 * `apap-runtime`（`ApapEngine`/`ApapAdmin`）に存在しないものがある。Gatewayに
 * ビジネスロジックを置くことは禁止されているため（CLAUDE.md / 本タスクの制約）、
 * それらは「Gateway側で実装する」のではなく「まだ提供していない」と明示する。
 *
 * 黙って501を返すのではなく、
 * - この表を単一の情報源として`GET /v1/_endpoints`で機械可読に公開し、
 * - 未提供エンドポイントには「何が無いから提供できないのか」を`detail`に載せた
 *   [apap.gateway.error.ApiError.NotImplemented]を返し、
 * - `EndpointCatalogTest`が「表と実際に登録されたルートが一致すること」を機械検証する。
 *
 * これにより「実装したつもりで未配線」「表からも実装からも漏れる」の両方を防ぐ
 * （CLAUDE.md不変条件9の「シグナルの不在を成功と読まない」の適用）。
 */
data class EndpointSpec(
    val method: String,
    val path: String,
    val summary: String,
    val status: EndpointStatus,
    /** [EndpointStatus.NOT_IMPLEMENTED]のとき、提供できない理由（何が足りないか）。 */
    val unavailableReason: String? = null,
) {
    init {
        require(status == EndpointStatus.IMPLEMENTED || unavailableReason != null) {
            "NOT_IMPLEMENTED endpoints must state why they are unavailable: $method $path"
        }
        require(status != EndpointStatus.IMPLEMENTED || unavailableReason == null) {
            "IMPLEMENTED endpoints must not carry an unavailableReason: $method $path"
        }
    }
}

enum class EndpointStatus {
    /** ルートが登録され、実際にApapEngine/ApapAdminへ委譲される。 */
    IMPLEMENTED,

    /** 13.1には定義があるが、対応するユースケースがapap-runtimeに無いため提供していない。 */
    NOT_IMPLEMENTED,
}

/**
 * 13.1の表を上から順に写したもの。**設計書の行を削らないこと**——提供していないものも
 * [EndpointStatus.NOT_IMPLEMENTED]として必ず残す（表から消すと「無かったこと」になり、
 * まさに黙って落とすのと同じになる）。
 */
object EndpointCatalog {
    private const val NEEDS_BATCH_USE_CASE =
        "apap-runtimeにBatchジョブのユースケース（投入・状態取得・結果取得・キャンセル）が公開されていない。" +
            "BatchJobRepository/BatchJob Aggregateは存在するが、それを駆動するManager/UseCaseとApapEngine上の" +
            "公開口が未実装のため、Gateway側で組み立てるとビジネスロジックの越境になる。"

    private const val NEEDS_MEMORY_USE_CASE =
        "apap-runtimeにMemory操作の公開口が無い。MemoryManager（apap-context）は存在するが" +
            "ApapEngineから到達できないため、Gatewayから直接呼ぶとレイヤ違反になる。"

    private const val NEEDS_SESSION_USE_CASE =
        "apap-runtimeにSession操作の公開口が無い（SessionManagerはapap-contextに存在するが未公開）。"

    private const val NEEDS_CONVERSATION_USE_CASE =
        "apap-runtimeにConversation操作の公開口が無い（ConversationManagerはapap-contextに存在するが未公開）。"

    private const val NEEDS_ADMIN_SURFACE =
        "ApapAdminが対応するオペレーションを公開していない（ApapAdminのKDoc参照: " +
            "quotas/analytics等はP9のスコープ外として意図的に未実装）。"

    private const val NEEDS_ALIAS_LISTING =
        "AliasRepositoryにテナント単位の一覧取得（listByTenant相当）が無く、findByName(name)しか引けないため" +
            "一覧を組み立てられない。Portの追加が先に必要。"

    private const val NEEDS_PROVIDER_UPDATE =
        "ProviderManagerに汎用の更新（PATCH）オペレーションが無い。状態遷移は:enable/:drain/:validateで提供済み。"

    private const val NEEDS_ROTATION_USE_CASE =
        "CredentialRotationService（apap-domain）は存在するが、ApapAdminにrotationを駆動する口が無い。"

    private const val NEEDS_DISCOVERY_USE_CASE =
        "ProviderAdapter.discoverModels()はSPIに存在するが、検出結果を保持・一覧するユースケースがapap-runtimeに無い。"

    private const val NEEDS_PLUGIN_ADMIN =
        "PluginManager（apap-plugin）はApapEngineBuilderが内部で使うのみで、ApapAdminからscan/一覧を公開していない。"

    private const val NEEDS_CACHE_ADMIN =
        "CacheStore/CacheEngineに対する無効化オペレーションがApapAdminに公開されていない。"

    private const val NEEDS_AUDIT_QUERY =
        "AuditRepository.search（apap-observability）は存在するが、ApapAdminから公開されていない。"

    val entries: List<EndpointSpec> =
        listOf(
            // --- 実行系API（13.1）。すべてCapability非依存の execute/executeStream へ委譲する。 ---
            impl("POST", "/v1/chat", "Chat（stream=trueでSSE）"),
            impl("POST", "/v1/completions", "Completion"),
            impl("POST", "/v1/embeddings", "Embedding生成"),
            impl("POST", "/v1/images/generations", "画像生成"),
            impl("POST", "/v1/images/edits", "画像編集"),
            impl("POST", "/v1/images/analyses", "画像解析"),
            impl("POST", "/v1/audio/transcriptions", "Speech To Text"),
            impl("POST", "/v1/audio/speech", "Text To Speech"),
            impl("POST", "/v1/audio/translations", "Audio Translation"),
            impl("POST", "/v1/videos/analyses", "動画解析"),
            notImpl("POST", "/v1/batches", "Batchジョブ投入", NEEDS_BATCH_USE_CASE),
            notImpl("GET", "/v1/batches/{job_id}", "Batch状態取得", NEEDS_BATCH_USE_CASE),
            notImpl("GET", "/v1/batches/{job_id}/results", "Batch結果取得", NEEDS_BATCH_USE_CASE),
            notImpl("DELETE", "/v1/batches/{job_id}", "Batchキャンセル", NEEDS_BATCH_USE_CASE),
            notImpl("POST", "/v1/memories", "Memory登録", NEEDS_MEMORY_USE_CASE),
            notImpl("GET", "/v1/memories/{id}", "Memory取得", NEEDS_MEMORY_USE_CASE),
            notImpl("DELETE", "/v1/memories/{id}", "Memory削除", NEEDS_MEMORY_USE_CASE),
            notImpl("POST", "/v1/memories/search", "Memory検索", NEEDS_MEMORY_USE_CASE),
            // --- Discovery / Context系API（13.1） ---
            impl("GET", "/v1/capabilities", "利用可能Capability一覧（テナント権限適用済）"),
            impl("GET", "/v1/capabilities/{capability_id}", "Capability詳細（入出力JSON Schema、制約）"),
            notImpl("GET", "/v1/aliases", "利用可能Model Alias一覧", NEEDS_ALIAS_LISTING),
            notImpl("POST", "/v1/sessions", "Session作成", NEEDS_SESSION_USE_CASE),
            notImpl("DELETE", "/v1/sessions/{id}", "Session失効", NEEDS_SESSION_USE_CASE),
            notImpl("POST", "/v1/conversations", "Conversation作成", NEEDS_CONVERSATION_USE_CASE),
            notImpl("GET", "/v1/conversations/{id}", "Conversation取得", NEEDS_CONVERSATION_USE_CASE),
            notImpl("GET", "/v1/conversations/{id}/turns", "Conversation履歴取得", NEEDS_CONVERSATION_USE_CASE),
            notImpl("DELETE", "/v1/conversations/{id}", "Conversation削除", NEEDS_CONVERSATION_USE_CASE),
            // --- 管理系API（13.1、Admin権限） ---
            impl("POST", "/admin/v1/providers", "Provider登録"),
            impl("GET", "/admin/v1/providers", "Provider一覧"),
            impl("GET", "/admin/v1/providers/{id}", "Provider取得"),
            notImpl("PATCH", "/admin/v1/providers/{id}", "Provider更新", NEEDS_PROVIDER_UPDATE),
            impl("DELETE", "/admin/v1/providers/{id}", "Provider論理削除"),
            impl("POST", "/admin/v1/providers/{id}:enable", "Provider有効化"),
            impl("POST", "/admin/v1/providers/{id}:drain", "Provider排出開始"),
            impl("POST", "/admin/v1/providers/{id}:disable", "Provider無効化（排出完了）"),
            impl("POST", "/admin/v1/providers/{id}:validate", "Provider疎通・Credential検証"),
            notImpl(
                "POST",
                "/admin/v1/providers/{id}/credentials:rotate",
                "Credential Rotation",
                NEEDS_ROTATION_USE_CASE,
            ),
            impl("POST", "/admin/v1/models", "Model登録"),
            impl("GET", "/admin/v1/models", "Model一覧（provider_id または capability_id で絞り込み必須）"),
            notImpl("GET", "/admin/v1/models:discovered", "Discovery検出済み候補一覧", NEEDS_DISCOVERY_USE_CASE),
            impl("PATCH", "/admin/v1/models/{id}", "Model更新（status変更）"),
            impl("PUT", "/admin/v1/aliases/{name}", "Alias付替（Canary weight）"),
            impl("GET", "/admin/v1/aliases/{name}", "Alias取得"),
            impl("POST", "/admin/v1/policies", "Routing Policy登録"),
            impl("GET", "/admin/v1/policies", "Routing Policy一覧（有効ポリシー解決）"),
            impl("PUT", "/admin/v1/policies", "Routing Policy更新"),
            notImpl("PUT", "/admin/v1/quotas/{tenant_id}", "Quota設定", NEEDS_ADMIN_SURFACE),
            notImpl("PUT", "/admin/v1/budgets/{tenant_id}", "Budget設定", NEEDS_ADMIN_SURFACE),
            notImpl("GET", "/admin/v1/analytics/usage", "Usage集計", NEEDS_ADMIN_SURFACE),
            notImpl("GET", "/admin/v1/analytics/cost", "Cost集計", NEEDS_ADMIN_SURFACE),
            notImpl("GET", "/admin/v1/analytics/errors", "Error集計", NEEDS_ADMIN_SURFACE),
            notImpl("GET", "/admin/v1/audit", "監査検索", NEEDS_AUDIT_QUERY),
            impl("GET", "/admin/v1/health/providers", "Provider Health集約"),
            notImpl("POST", "/admin/v1/plugins:scan", "Pluginスキャン", NEEDS_PLUGIN_ADMIN),
            notImpl("GET", "/admin/v1/plugins", "Plugin一覧", NEEDS_PLUGIN_ADMIN),
            notImpl("POST", "/admin/v1/caches:invalidate", "Cache無効化", NEEDS_CACHE_ADMIN),
            // --- 13.1の表には無い運用エンドポイント（本タスクの指示7）。 ---
            impl("GET", "/healthz", "Liveness"),
            impl("GET", "/readyz", "Readiness"),
            impl("GET", "/metrics", "OpenMetrics形式のメトリクス"),
            impl("GET", "/v1/_endpoints", "本カタログ（各エンドポイントの提供状況）"),
        )

    val implemented: List<EndpointSpec> get() = entries.filter { it.status == EndpointStatus.IMPLEMENTED }
    val notImplemented: List<EndpointSpec> get() = entries.filter { it.status == EndpointStatus.NOT_IMPLEMENTED }

    fun find(
        method: String,
        path: String,
    ): EndpointSpec? = entries.firstOrNull { it.method.equals(method, ignoreCase = true) && it.path == path }

    private fun impl(
        method: String,
        path: String,
        summary: String,
    ) = EndpointSpec(method, path, summary, EndpointStatus.IMPLEMENTED)

    private fun notImpl(
        method: String,
        path: String,
        summary: String,
        reason: String,
    ) = EndpointSpec(method, path, summary, EndpointStatus.NOT_IMPLEMENTED, reason)
}
