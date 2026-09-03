package apap.runtime.fidelity

import apap.adapter.spi.AdapterRequest
import apap.api.ApapRequest
import apap.domain.model.execution.CanonicalRequest
import java.lang.reflect.Modifier

/**
 * 公開リクエスト型（[ApapRequest] / [CanonicalRequest]）の**全フィールド**について、
 * 「Adapterへ到達すべきか」を宣言する単一の場所。
 *
 * ## なぜこの表が要るのか
 *
 * APAPの中核機能は共通リクエストをProviderリクエストへ変換することであり、
 * 最も検証されるべき地点は「Adapterが何を受け取ったか」である。ところがP11時点のテストは
 * 「応答が返る」「イベントが飛ぶ」という**結果**を見ており、変換の中身を見ていなかった。
 * その結果、同じ形の欠落が2件出た。
 *
 * - F4: `messages[].role` が3箇所で独立に脱落（System Promptの供給経路が存在しなかった）
 * - F3: `outputSchema` を検証する箇所が無く、スキーマ違反応答が正常応答として返っていた
 *
 * 2件出た以上、他のフィールドにも同じ穴があると考えるべきである。この表は
 * [MetricsCoverageTest][apap.observability.metrics.MetricsCoverageTest] /
 * [DomainEventCoverageTest][apap.domain.event.DomainEventCoverageTest] と同じクローズドセット方式で、
 * **新しいフィールドを足したのに分類を書かなければビルドが落ちる**ようにする
 * （[RequestFidelityContractTest]）。分類だけでは「書いたつもり」に過ぎないため、
 * 実際の到達性は[RequestFidelityE2ETest]が実エンジン経由で確かめる。
 */
object RequestFidelityContract {
    /**
     * フィールド1つの扱い。3分類は排他で、どれにも当てはまらないフィールドは存在してはならない。
     */
    sealed interface Disposition {
        /** なぜそう分類したのか。表を読んだ人が判断を再現できる粒度で書く。 */
        val reason: String

        /**
         * Adapterへ到達すべきフィールド。
         *
         * @param adapterField [AdapterRequest]側の受け取り先フィールド名
         * @param transform 変換後の形（そのままなら "as-is"）
         */
        data class ReachesAdapter(
            val adapterField: String,
            val transform: String,
            override val reason: String,
        ) : Disposition

        /**
         * Adapterへ到達してはならないフィールド。テナント内部情報・利用者識別子など、
         * Providerに渡すと外部へ出てしまうもの。
         */
        data class NeverReachesAdapter(
            override val reason: String,
        ) : Disposition

        /**
         * APAP内部で消費されるフィールド。Adapterには渡らない（渡す意味が無い）が、
         * 秘匿が目的ではないため[NeverReachesAdapter]とは分ける。
         *
         * @param consumer 消費する側（クラス名や処理名）
         */
        data class ConsumedInternally(
            val consumer: String,
            override val reason: String,
        ) : Disposition
    }

    /**
     * 到達しないフィールドの実値を追跡するための見張り文字列。
     *
     * `null`は「文字列として追跡できない」ことを明示的に宣言する枠で、[noSentinelReason]に
     * 理由を書く。宣言し忘れとの区別が付くよう、[RequestFidelityContractTest]が
     * 「到達しないフィールドは全て[Probe]を持つ」ことを検査する。
     */
    data class Probe(
        val sentinel: String?,
        val noSentinelReason: String? = null,
    ) {
        init {
            require(sentinel != null || noSentinelReason != null) {
                "sentinelを置けないなら理由を書くこと（黙って追跡対象から外さない）"
            }
        }
    }

    /** [ApapRequest]の全フィールド。キーはフィールド名。 */
    val apapRequestFields: Map<String, Disposition> =
        mapOf(
            "tenantId" to
                Disposition.NeverReachesAdapter(
                    "テナント識別子。Providerへ渡すと利用企業の内訳が外部へ出る（不変条件3の趣旨）",
                ),
            "principal" to
                Disposition.NeverReachesAdapter(
                    "エンドユーザ識別子（CIAP subject）。Providerへ渡す必要が無く、渡せば個人特定に繋がる",
                ),
            "capabilityId" to
                Disposition.ReachesAdapter(
                    adapterField = "capabilityId",
                    transform = "as-is（ADR-0016のtypealiasで同一クラス）",
                    reason = "どのCapabilityの呼出かはAdapterが実Provider APIを選ぶのに要る",
                ),
            "input" to
                Disposition.ReachesAdapter(
                    adapterField = "input",
                    transform = "Prompt Pipeline適用 → Context組立（System Prompt/Memory/履歴を前置）した平坦列",
                    reason = "roleを見ない用途（トークン計上等）向けの平坦表現。roleが要る変換はmessagesを使う",
                ),
            "modelAlias" to
                Disposition.ConsumedInternally(
                    consumer = "RoutingEngine（AliasRepository経由でModelへ解決）",
                    reason = "Aliasは論理名で、Adapterが受け取るのは解決後の物理名modelName（不変条件3）",
                ),
            "params" to
                Disposition.ReachesAdapter(
                    adapterField = "params",
                    transform =
                        "apap.domain.model.execution.GenerationParams → apap.adapter.spi.GenerationParamsへ" +
                            "フィールド単位変換（temperature/maxTokens/topP/stop/seed）",
                    reason = "生成パラメタはProvider APIへ渡す値そのもの",
                ),
            "tools" to
                Disposition.ReachesAdapter(
                    adapterField = "tools",
                    transform = "domain ToolDefinition → spi ToolDefinition（name/description/parametersSchema）",
                    reason = "FR-CAP-005 Tool Calling。Provider側のtool定義へAdapterが変換する",
                ),
            "toolResults" to
                Disposition.ReachesAdapter(
                    adapterField = "toolResults",
                    transform = "domain ToolResult → spi ToolResult（callId/content/isError）",
                    reason = "5.4後半のTool往復。実行は利用側、APAPは結果を中継するだけ",
                ),
            "outputSchema" to
                Disposition.ReachesAdapter(
                    adapterField = "outputSchema",
                    transform = "as-is（JSON Schema文字列）",
                    reason = "FR-CAP-003 Structured Output。APAP側でも応答検証に使うが、Providerにも渡す",
                ),
            "conversationId" to
                Disposition.ConsumedInternally(
                    consumer = "DefaultContextManager（履歴読込）/ ExecutionEngine.recordUserTurn（履歴書込）",
                    reason = "履歴はAPAPが解決してmessagesへ展開する。ID自体はAPAP内部の識別子",
                ),
            "sessionId" to
                Disposition.NeverReachesAdapter(
                    "セッション識別子。テナント内の利用者追跡に使える識別子であり外部へ出さない。" +
                        "現状は実行経路でも読まれない（P11-F5: SessionManagerが未配線）",
                ),
            "idempotencyKey" to
                Disposition.ConsumedInternally(
                    consumer = "DefaultCacheEngine（Request Cache）/ IdempotencyGuard",
                    reason = "重複実行の抑止はAPAPの責務。Providerには関係が無い",
                ),
            "timeoutBudget" to
                Disposition.ReachesAdapter(
                    adapterField = "timeout",
                    transform = "経過時間を差し引いた残予算（ExecutionContext.remaining）",
                    reason = "Adapterが実Provider呼出のタイムアウトに使う。全体予算ではなく残りを渡す",
                ),
            "requestId" to
                Disposition.ConsumedInternally(
                    consumer = "イベント（14章）/ 監査 / Quota予約 / キャッシュキー",
                    reason = "APAP内の相関ID。Providerへ渡す用途が無い",
                ),
            "traceId" to
                Disposition.ReachesAdapter(
                    adapterField = "traceHeaders",
                    transform = """mapOf("traceparent" to traceId)""",
                    reason = "分散トレースの伝播（NFR-OBS-003）。Provider側ログとの突き合わせに要る",
                ),
            "messages" to
                Disposition.ReachesAdapter(
                    adapterField = "messages",
                    transform = "System Prompt/Memory/履歴を前置し、roleを保ったまま連結（ADR-0031）",
                    reason = "**Provider固有形式への変換はこちらを使う**。inputは平坦でroleを区別できない",
                ),
        )

    /** [CanonicalRequest]の全フィールド。[ApapRequest]に無い内部フィールドを足したもの。 */
    val canonicalRequestFields: Map<String, Disposition> =
        apapRequestFields +
            mapOf(
                "constraints" to
                    Disposition.ConsumedInternally(
                        consumer = "RoutingEngine（ハードフィルタ: region/maxCost/maxLatency/excludeProviders）",
                        reason = "候補の絞り込み条件。どのProviderを選ぶかの入力であり、選ばれた後には意味が無い",
                    ),
                "preferences" to
                    Disposition.ConsumedInternally(
                        consumer = "RoutingEngine（スコアリングのoptimizeFor）",
                        reason = "候補の順位付け条件。constraintsと同じ理由でAdapterには渡らない",
                    ),
            )

    /**
     * 到達しない（[Disposition.NeverReachesAdapter] / [Disposition.ConsumedInternally]）
     * フィールドを実測で追跡するための見張り値。[RequestFidelityE2ETest]が
     * 捕捉した[AdapterRequest]の文字列表現に**現れないこと**を確認する。
     */
    val probes: Map<String, Probe> =
        mapOf(
            // EngineFixture.TENANTと同じULID。ルーティング/ポリシー解決に実在のテナントが要るため
            // 任意文字列にはできないが、ULIDは十分に区別可能な見張り値になる。
            "tenantId" to Probe("01ARZ3NDEKTSV4RRFFQ69G5FZ0"),
            "principal" to Probe("sentinel-principal-4f2a"),
            "modelAlias" to Probe("sentinel-alias-7b2e"),
            "conversationId" to Probe("01ARZ3NDEKTSV4RRFFQ69G5FZ7"),
            "sessionId" to Probe("01ARZ3NDEKTSV4RRFFQ69G5FZ8"),
            "idempotencyKey" to Probe("sentinel-idempotency-3d5c"),
            "requestId" to Probe("01ARZ3NDEKTSV4RRFFQ69G5FZ9"),
            "constraints" to Probe("424243"), // RoutingConstraints.maxLatencyMs
            "preferences" to
                Probe(
                    sentinel = null,
                    noSentinelReason =
                        "RoutingPreferencesはOptimizeFor列挙のみで、任意の見張り値を入れられない。" +
                            "到達しないことはRoutingEngineが唯一の読み手であること（構造）で担保する",
                ),
        )

    /**
     * リクエスト由来ではない[AdapterRequest]のフィールド。
     * 「どのリクエストフィールドからも埋められないAdapterフィールド」を検出するための除外枠で、
     * ここに書かない限り[RequestFidelityContractTest]は未対応として落とす。
     */
    val adapterOnlyFields: Map<String, String> =
        mapOf(
            "modelName" to "Routingが決めたModelの物理名。リクエストにはmodelAlias（論理名）しか無い（不変条件3）",
            "authContext" to "Adapter自身のauthenticate()の結果。Credentialは保持しない（不変条件4）",
        )

    /**
     * data classの宣言フィールド名。Kotlinのプロパティはbacking fieldとして現れるため、
     * static（Companion等）と合成フィールドを除けばそのままフィールド一覧になる。
     * kotlin-reflectを足さずに済ませるためJavaリフレクションを使う。
     */
    fun declaredFieldsOf(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
}
