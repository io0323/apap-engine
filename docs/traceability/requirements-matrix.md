# トレーサビリティマトリクス

docs/design/01_要件定義.md の機能要件（FR-*）・非機能要件（NFR-*）と、実装クラス・テストクラスの対応表。
01_CLAUDE.md「トレーサビリティ」規約に従い、機能実装時に該当行の「実装クラス」「テストクラス」「状態」を更新すること。

状態は 未実装 / 実装中 / 実装済 のいずれか。

`modules/apap-domain` 実装セッション（P1後続）で「実装中」へ更新した行は、Domain層（Aggregate/VO/Domain
Service/Port/Event）のみが揃った状態を指す。Application/Infrastructure/Adapter/Presentation層と結線し
エンドツーエンドで要件を満たすまでは「実装済」へは進めない。クラス名はモジュール内での相対参照。

| 要件ID | 要件概要 | 実装クラス | テストクラス | 状態 |
|---|---|---|---|---|
| FR-PRV-001 | Providerを動的に登録・更新・削除できること。削除は論理削除とし、監査履歴を保持すること | apap.domain.model.provider.Provider, apap.provider.ProviderManager | ProviderTest, ProviderManagerTest | 実装中 |
| FR-PRV-002 | Providerを有効化・無効化（DRAINING経由）できること。無効化中の実行中リクエストは完遂させること | apap.domain.model.provider.Provider, apap.provider.ProviderManager（drain/enforceDrainTimeout/completeDraining） | ProviderTest, ProviderManagerTest, RoutingEngineTest（DRAINING除外・既存決定の不変性） | 実装中 |
| FR-PRV-003 | Provider毎にCapability・対応Model・リージョン・優先度・レート制限を保持すること | apap.domain.model.provider.Provider, RateLimits, Endpoint, apap.provider.ProviderManager | ProviderTest, ProviderManagerTest | 実装中 |
| FR-PRV-004 | ProviderはPlugin（Adapter実装）として追加可能とし、APAP本体の再ビルドを不要とすること | apap.domain.model.plugin.PluginRegistration, apap.adapter.spi.ProviderAdapter, apap.adapter.spi.plugin.PluginManifest/PluginManifestParser/SemVerRange, apap.provider.AdapterRegistry, apap.provider.ProviderManager（completeValidationでのCapability申告突合） | PluginRegistrationTest, PluginManifestParserTest, SemVerRangeTest, MockProviderAdapterContractTest, ProviderManagerTest | 実装中 |
| FR-PRV-005 | Provider毎にCredential（複数世代）を安全に管理し、無停止ローテーションできること | apap.domain.model.vo.CredentialRef, apap.domain.service.provider.CredentialRotationService | CredentialRefTest, CredentialRotationServiceTest | 実装中 |
| FR-PRV-006 | Providerの健全性（UP / DEGRADED / DOWN）を定期監視し、ルーティングへ反映すること | apap.domain.model.provider.ProviderHealthStatus, apap.domain.event.ProviderHealthChanged, RoutingHardFilters.passesHealthFilter, apap.routing.RoutingCandidateCache, apap.domain.port.HealthLatencyStatsRepository | RoutingDomainServiceTest, DomainEventInstantiationTest, RoutingCandidateCacheTest, CandidateFactoryTest | 実装中 |
| FR-MDL-001 | Modelを登録・一覧・更新でき、Provider・Version・Capability・コスト単価・コンテキスト長・制約を保持すること | apap.domain.model.modelcatalog.Model, ModelCapability, apap.domain.model.cost.PriceBook, apap.provider.ModelManager | ModelTest, PriceBookTest, ModelManagerTest | 実装中 |
| FR-MDL-002 | Model Statusを管理すること（REGISTERED / TESTING / ACTIVE / DEPRECATED / RETIRED） | apap.domain.model.modelcatalog.Model, apap.provider.ModelManager.changeStatus | ModelTest, ModelManagerTest | 実装中 |
| FR-MDL-003 | Model Alias（論理名）を管理し、Aliasから物理Modelへの解決を行うこと。利用側はAliasのみで指定可能なこと | apap.domain.model.modelcatalog.ModelAlias, AliasTarget, apap.provider.ModelManager.assignAlias, apap.routing.CandidateFactory | ModelAliasTest, ModelManagerTest, RoutingEngineTest | 実装中 |
| FR-MDL-004 | Alias切替時にトラフィック比率（Canary）を指定できること | apap.domain.model.modelcatalog.AliasTarget, apap.domain.service.provider.CanaryResolutionService, apap.provider.ModelManager.setCanaryWeight, apap.routing.RoutingEngine | CanaryResolutionServiceTest, ModelManagerTest, RoutingEngineTest（5.8シナリオ） | 実装中 |
| FR-MDL-005 | Provider AdapterのModel Discoveryにより、Provider側の新Modelを検出し登録候補として提示すること | apap.adapter.spi.ProviderAdapter.discoverModels, apap.adapter.spi.DiscoveredModel, apap.provider.ModelManager.discoverModels（自動登録はしない） | MockProviderAdapterContractTest, ModelManagerTest | 実装中 |
| FR-CAP-001 | Chat（Multi Turn / System Prompt / User Prompt）を提供すること | - | - | 未実装 |
| FR-CAP-002 | Completionを提供すること | - | - | 未実装 |
| FR-CAP-003 | Structured Output（JSON Schema指定、出力検証、違反時の是正リトライ）を提供すること | - | - | 未実装 |
| FR-CAP-004 | Streaming（Chat / Completion / TTSの逐次応答、SSEおよびgRPC stream）を提供すること | apap.execution.ExecutionEngine.executeStream, apap.execution.streaming.StreamingRequestExecutor（着手前レビューで解消: Prompt→Cache-bypass→Routing→Quota予約→StreamingEngine→Flow<StreamChunk>の配線、初回チャンク送出前のみFallback対象、message_end時のQuota commit/Cost記録、中断時の部分commit、StreamingTurnRecorder接続）, apap.execution.streaming.StreamingEngine/StreamingTurnRecorder | StreamingEngineTest, StreamingTurnRecorderTest, CapabilitySmokeTest（streaming chat: チャンク列・Usage確定・Turn記録をE2E検証） | 部分実装（`Flow<StreamChunk>`を返す内部オーケストレーションは実配線・E2E検証済み。SSE/gRPCのワイヤープロトコル変換自体はGateway層、P10の対象） |
| FR-CAP-005 | Function Calling / Tool Calling（定義の共通形式化、呼出指示の正規化、結果返信、並列呼出）を提供すること | - | - | 未実装 |
| FR-CAP-006 | Embedding（単発・Batch、次元数指定）を提供すること | - | - | 未実装 |
| FR-CAP-007 | Vision（画像入力理解）/ Image Analysis を提供すること | - | - | 未実装 |
| FR-CAP-008 | Image Generation / Image Editing を提供すること | - | - | 未実装 |
| FR-CAP-009 | Speech To Text / Text To Speech / Audio Translation を提供すること | - | - | 未実装 |
| FR-CAP-010 | Video Analysis を提供すること | - | - | 未実装 |
| FR-CAP-011 | Video Generation を提供可能な拡張構造とすること | - | - | 未実装 |
| FR-CAP-012 | Reasoning（推論強度指定、推論過程の取得可否の正規化）を提供すること | - | - | 未実装 |
| FR-CAP-013 | Memory（会話横断の長期記憶の保存・検索・注入）を提供すること | apap.domain.model.conversation.Memory | SessionAndMemoryTest | 実装中 |
| FR-CAP-014 | RAG（外部知識の検索・コンテキスト注入のパイプライン接続点）を提供すること | - | - | 未実装 |
| FR-CAP-015 | Fine Tuning のジョブ管理APIを抽象化可能な拡張構造とすること | - | - | 未実装 |
| FR-CAP-016 | Batch Processing（非同期一括実行、ジョブ状態管理、結果取得）を提供すること | apap.domain.model.execution.BatchJob, BatchItem | BatchJobTest | 実装中 |
| FR-CAP-017 | 新Capabilityをスキーマ登録のみで追加可能とすること（Capability Registry） | apap.domain.model.capability.CapabilityDefinition, apap.domain.port.CapabilityRepository, apap.provider.CapabilityRegistry（`com.networknt:json-schema-validator`委譲）, apap.provider.CapabilityDiscoveryQuery | CapabilityDefinitionTest, CapabilityRegistryTest, CapabilityDiscoveryQueryTest | 実装中 |
| FR-RTE-001 | Capability・Model制約を満たす候補（Provider×Model）を解決すること | apap.domain.service.routing.RoutingHardFilters, apap.routing.CandidateFactory | RoutingDomainServiceTest, CandidateFactoryTest（a〜g各条件の単独除外） | 実装中 |
| FR-RTE-002 | Cost / Latency / Availability / Region / Priority を重み付きスコアで評価し最適候補を選択すること | apap.domain.service.routing.RoutingDomainService.computeScores, apap.routing.spi.WeightedScoreRoutingStrategy, apap.routing.RealCostEstimator（P7でZeroCostEstimatorの既定を置換、PriceBookRepositoryから実単価を引きS_costへ反映）, apap.routing.CandidateFactory.toCandidate（ADR-0021: 単価未登録Modelはペナルティではなくハードフィルタ相当で除外、Candidate自体を組み立てない） | RoutingDomainServiceTest, RoutingEngineTest（optimize_for=cost実効性テスト追加）, RealCostEstimatorTest, CandidateFactoryTest（単価未登録の除外を検証） | 実装済 |
| FR-RTE-003 | Policy（Platform / Tenant / Workflow / User Preferenceの4階層、優先順は後者ほど強いが上位の禁止事項は覆せない）を適用すること | apap.domain.model.routing.RoutingPolicy, PolicyRule, apap.domain.service.routing.PolicyResolutionService, apap.routing.RoutingEngine | RoutingPolicyTest, PolicyResolutionServiceTest, RoutingEngineTest, CapabilityDiscoveryQueryTest | 実装中 |
| FR-RTE-004 | Fallback Chain（最大N段、既定3段）を構成し、失敗分類に応じて次候補へ自動移行すること | apap.domain.service.routing.RoutingDomainService.buildFallbackChain, apap.domain.service.execution.ErrorClassificationService, apap.routing.RoutingEngine | RoutingDomainServiceTest, ErrorClassificationServiceTest, RoutingEngineTest | 実装中 |
| FR-RTE-005 | Load Balancer（同スコア帯候補への重み付きラウンドロビン）を提供すること | apap.domain.service.routing.RoutingDomainService.selectViaLoadBalancing, apap.routing.spi.WeightedRoundRobinLoadBalancer | RoutingDomainServiceTest, RoutingEngineTest | 実装中 |
| FR-RTE-006 | ルーティング決定の根拠（候補・スコア・適用Policy）を記録すること | apap.domain.service.routing.ScoredCandidate, FallbackChain, apap.domain.model.audit.AuditRecord.routingDecision, apap.routing.RoutingDecision | RoutingDomainServiceTest, AuditRecordTest, RoutingEngineTest | 実装中 |
| FR-RTE-007 | Sticky Routing（同一Conversation内は同一Model優先）を提供すること | apap.domain.service.routing.RoutingDomainService.applyStickyBonus, apap.routing.RoutingEngine | RoutingDomainServiceTest, RoutingEngineTest | 実装中 |
| FR-PMT-001 | Prompt Pipeline（Validation → Optimization → Rendering）を提供すること | apap.prompt.PromptStage（16.7 SPI、任意位置挿入可）, apap.prompt.PromptPipeline, apap.prompt.DefaultPromptEngine | PromptPipelineTest, DefaultPromptEngineTest | 実装中（Rendering段は既定Pipelineではパススルー。CanonicalRequestにテンプレート参照フィールドが無くライブリクエストから解決不能なため） |
| FR-PMT-002 | Prompt Validation（サイズ上限、禁止パターン、インジェクション検査、Schema整合）を行うこと | apap.prompt.PromptValidator/PromptValidationConfig, apap.prompt.ValidationStage | PromptValidatorTest | 実装中 |
| FR-PMT-003 | Prompt Optimization（トークン圧縮、履歴要約、テンプレート変数解決）を行うこと | apap.prompt.PromptOptimizer/OptimizationStage（トークン圧縮・変数解決）, apap.context.SummarizeCompactionStrategy（履歴要約、02_システム仕様.md 2.16の圧縮戦略側） | PromptOptimizerTest, SummarizeCompactionStrategyTest | 実装中 |
| FR-PMT-004 | Prompt Template（変数、条件分岐、バージョン管理）を管理すること | apap.domain.model.prompt.PromptTemplate, TemplateVariable, apap.domain.port.PromptTemplateRepository, apap.prompt.PromptTemplateManager, apap.prompt.TemplateRenderEngine, apap.prompt.PromptRenderer | PromptTemplateTest, PromptTemplateManagerTest, TemplateRenderEngineTest | 実装中（CRUD/バージョニング/描画は完結。RenderingStageからのライブ結線は未接続、FR-PMT-001参照） |
| FR-EXE-001 | Retry（指数バックオフ+ジッター、リトライ可否のエラー分類、最大試行回数、全体タイムアウト予算）を提供すること | apap.domain.service.execution.ErrorClassificationService（エラー分類）, apap.execution.retry.ExponentialBackoffJitterStrategy/RetryConfig, apap.execution.attempt.AttemptExecutor（試行ループ・予算管理・ADR-0011是正リトライ） | ErrorClassificationServiceTest, ExponentialBackoffJitterStrategyTest, AttemptExecutorTest | 実装中 |
| FR-EXE-002 | Circuit Breaker（Provider×Model単位、CLOSED/OPEN/HALF_OPEN）を提供すること | apap.domain.model.execution.CircuitBreakerState/CbState/WindowStats（状態機械）, apap.domain.port.CircuitBreakerStateStore, apap.execution.circuitbreaker.CircuitBreaker（tryAcquire/recordSuccess/recordFailure、ADR-0001準拠のin-memory本番実装 apap.execution.adapter.out.InMemoryCircuitBreakerStateStore） | CircuitBreakerStateTest, CircuitBreakerTest | 実装中 |
| FR-EXE-003 | Rate Limiter（Provider制限の遵守 + テナント別流量制御、Token Bucket方式）を提供すること | apap.cache.ratelimit.RateLimiter/TokenBucketRateLimiter/RateLimiterConfig（ADR-0001準拠、テナント/Provider2段） | TokenBucketRateLimiterTest | 部分実装（Token Bucketアルゴリズム自体は実装・テスト済。Provider.rateLimitsからバケット容量を自動構成する配線はapap-runtime側の追加配線待ち、既定値での動作は可能） |
| FR-EXE-004 | Quota（テナント/用途/期間単位のリクエスト数・トークン数・コスト上限）を提供すること | apap.domain.model.cost.QuotaPolicy, QuotaLimits, apap.cost.quota.QuotaManager/DefaultQuotaManager/Reservation（予約→commit/release/TTL失効、P0保留のC2/U16解消）, apap.domain.service.cost.PeriodWindowService（P7で期間境界計算を追加しTenantLedgerの期間別リセットを実装、プロセス起動からの累積課題を解消） | BudgetAndQuotaPolicyTest, DefaultQuotaManagerTest（期間境界を跨いだリセット/境界内非リセットのテスト追加） | 実装済 |
| FR-EXE-005 | Request Cache（同一リクエストの重複抑止）/ Response Cache（決定的要求の応答再利用、TTL・無効化）を提供すること | apap.cache.CacheEngine（Port）, apap.cache.DefaultCacheEngine（P7でPassthroughCacheEngineを置換）, apap.cache.CacheStore/InMemoryCacheStore, apap.cache.CacheKeyStrategy/NormalizedJsonCacheKeyStrategy, apap.cache.CacheabilityPolicy/DefaultCacheabilityPolicy, apap.execution.IdempotencyGuard（処理中の並行二重実行防止） | IdempotencyGuardTest, DefaultCacheEngineTest, NormalizedJsonCacheKeyStrategyTest | 実装済（既定のCacheStoreはIn-Memory。分散KVS実装はP8想定） |
| FR-EXE-006 | Scheduler（Batch実行計画、Health Check周期実行、Rotation周期実行）を提供すること | - | - | 未実装 |
| FR-EXE-007 | リクエストのタイムアウト（接続・応答・ストリームアイドル・全体）を制御できること | apap.domain.model.execution.ExecutionContext（締切ベースの残余予算計算）, apap.execution.attempt.AttemptExecutor（試行毎の予算チェック）, apap.execution.streaming.StreamingEngine/StreamingConfig（アイドル60s/全体300s既定） | AttemptExecutorTest, StreamingEngineTest | 実装中 |
| FR-CTX-001 | Session（利用側との論理接続、有効期限、属性）を管理すること | apap.domain.model.conversation.Session, apap.context.SessionManager（発行/検証/失効、スライディング更新） | SessionAndMemoryTest, SessionManagerTest | 実装中 |
| FR-CTX-002 | Conversation（Multi Turn履歴、Turn単位の永続化）を管理すること | apap.domain.model.conversation.Conversation, Turn, apap.context.ConversationManager（並行追記時もseq欠番なしを保証する楽観的リトライ）, apap.execution.ExecutionEngine（着手前レビューでTurn永続化を実行フローへ接続: user turnはProvider呼出前、assistant turnは応答確定後、Retry/Fallbackを跨いでも1リクエスト=各1件、永続化失敗は応答を失敗させずログのみ）, apap.execution.streaming.StreamingTurnRecorder（Streaming版、message_end/中断時の記録） | ConversationTest, ConversationManagerTest, ExecutionEngineComposerTest（履歴蓄積・冪等性・永続化失敗時の応答継続を検証）, StreamingTurnRecorderTest | 実装済（Turn永続化の書込側は実行フローに接続済み。executeStream自体の全体配線（Routing/Quota/Adapter呼出、apap-runtime未配線）は別課題として対象外） |
| FR-CTX-003 | Context Window管理（Model上限に応じた履歴の切詰め・要約圧縮戦略）を行うこと | apap.domain.service.conversation.ContextAssemblyService, apap.context.DefaultContextManager（build/refit）, apap.context.CompactionStrategy（TruncateOldest/Importance/Summarize） | ContextAssemblyServiceTest, DefaultContextManagerBudgetTest, DefaultContextManagerRefitTest, TruncateOldestCompactionStrategyTest, ImportanceCompactionStrategyTest, SummarizeCompactionStrategyTest | 実装中（既定のTruncateOldestは完全実装・ExecutionEngineComposerに配線済。SummarizeCompactionStrategyは要約用SummarizationPort未接続のためスタブ規約に従いoptedIn必須、既定では使われない） |
| FR-CTX-004 | Memory（長期記憶の保存・ベクトル検索・関連注入）を提供すること | apap.domain.model.conversation.Memory, apap.domain.port.MemoryRepository（tenantId/scopesでの絞り込みを追加、境界分離テストあり）, apap.context.MemoryManager, apap.runtime.ResilientQueryEmbedder（ADR-0023: CircuitBreaker/RateLimiterを経由するdecorator、delegateの実embedding呼出自体はP8以降） | SessionAndMemoryTest, MemoryManagerTest（テナント/scope分離テスト含む）, ResilientQueryEmbedderTest | 部分実装（保存・検索自体はテナント/scope境界込みで完全実装。ExecutionEngineComposerが既定で配線する`NoOpQueryEmbedder`は常に空ベクトルを返すため、Chat実行時のMemory注入は実質無効。実装位置・Resilience機構の使用方針はADR-0023で決定済み、実embedding呼出自体はP8以降、解消まで明示的opt-in+WARNで検知可能） |
| FR-RSP-001 | Response Normalization（Provider固有応答 → 共通応答モデル変換、FinishReason正規化、Usage正規化）を行うこと | apap.domain.model.vo.FinishReason, Usage, apap.domain.model.execution.CanonicalResponse, apap.execution.mapping.RequestMapper/ResponseMapper（AdapterRequest/AdapterResponse⇄Canonical変換） | TokenCountAndUsageTest, AttemptExecutorTest（RequestMapper/ResponseMapper経由の実行系結合） | 実装中 |
| FR-RSP-002 | エラー正規化（Provider固有エラー → 共通エラーコード体系）を行うこと | apap.domain.model.vo.NormalizedError（cbRecordable追加）, ErrorCode, apap.domain.service.execution.ErrorClassificationService, apap.execution.mapping.ResponseMapper.normalizeError | NormalizedErrorTest, ErrorClassificationServiceTest, AttemptExecutorTest | 実装中 |
| FR-RSP-003 | Streamingチャンクの正規化（デルタ形式統一、ToolCallの逐次組立）を行うこと | apap.domain.model.execution.StreamChunk/StreamChunkType, apap.execution.streaming.StreamingEngine, ToolCallAssembler, apap.execution.mapping.ResponseMapper.normalizeChunk | StreamingEngineTest | 実装中 |
| FR-OBS-001 | Audit Log（Request / Response / ルーティング決定 / Cost / Duration / 実行Provider・Model）を改竄不能な形で記録すること | apap.domain.model.audit.AuditRecord, apap.domain.port.AuditRepository, apap.infrastructure.eventbus.SynchronousEventBus, apap.observability.audit.AuditEngine（Event Bus購読、requestIdでRequestReceived〜RequestCompleted/RequestFailedを相関、同期実行パスをブロックしない非同期永続化） | AuditRecordTest, SynchronousEventBusTest, AuditEngineTest | 実装中（追記専用ストアはIn-Memory実装のみ、JDBC実装とGateway検索APIは未着手） |
| FR-OBS-002 | Metrics（Latency分位点、Error Rate、Token Usage、Cost、Availability、Cache Hit率、Fallback率）を出力すること | apap.domain.port.MetricsRecorder（2.19表の全11メトリクスを定義）, apap.observability.metrics.OpenTelemetryMetricsRecorder（OpenTelemetry Metrics API実装）, apap.observability.metrics.MetricsEngine（Event Bus購読、requests_total/duration/tokens_total/cost_total/retries_total/fallbacks_total/circuit_breaker_state/streaming_connections/rate_limit_events_total{reject}を記録） | MetricsEngineTest, InMemoryMetricsRecorderを使う各種テスト | 実装中（cache_events_total・overhead_duration_seconds・provider_health・rate_limit_events_total{wait}はメソッドは存在するが呼び出し元が未配線、MetricsEngineのKDocに明記。Cache層/Health Store/PhaseTimingsの配線は別タスク） |
| FR-OBS-003 | 分散Tracing（W3C Trace Context伝播、Adapter呼出までのSpan）を提供すること | apap.execution.ExecutionEngine（`DefaultExecutionEngine`内`PhaseTimings`、Span構成 apap.execute(root) → prompt/cache-lookup/routing/context/token-estimate/execution/mapping）, apap.execution.attempt.AttemptExecutor（execution配下にattempt[n]、adapter呼出毎）, apap.runtime.ExecutionEngineComposer（`Tracer`注入、既定はOpenTelemetry.noop()）。OpenTelemetry Tracing APIのみに依存、SDKは宿主が注入（CLAUDE.md不変条件6）。Span/Contextは常に引数で明示的に受け渡し、`Span.current()`/`makeCurrent()`は使わない（不変条件5、PhaseTimings/AttemptExecutorのKDoc参照） | AttemptExecutorTest（`each retry attempt is exported as its own Span`）, CapabilitySmokeTest（`chat capability exports the expected span hierarchy via a real Tracer`、実OpenTelemetry SDK経由でSpan階層をE2E検証） | 実装中（gatewayのSpanは`apap-gateway`（P10）未実装のため`apap.execute`が実質的なroot。StreamingRequestExecutor/FallbackChainのSpan計装は別タスク） |
| FR-OBS-004 | Usage Analytics（テナント/Capability/Model別の集計API）を提供すること | - | - | 未実装 |
| FR-OBS-005 | Cost Management（単価表管理、リクエスト毎コスト算出、Budget、閾値アラート）を提供すること | apap.domain.model.cost.PriceBook, Budget, apap.domain.service.cost.CostCalculationService, apap.domain.event.CostThresholdExceeded, apap.cost.CostEngine/DefaultCostEngine（P7でPassthroughCostEngineを置換、estimate/calculateはPriceEntryNotFoundExceptionで単価未登録を検出、recordはUsageRepository記録とBudget閾値監視を行う）。単価未登録Modelはルーティング段階で候補から除外されるため（ADR-0021）、通常の実行フローではPriceEntryNotFoundExceptionへは到達しない（不整合時の最終防御として保持） | PriceBookTest, BudgetAndQuotaPolicyTest, CostCalculationServiceTest, DefaultCostEngineTest | 実装済 |
| FR-OBS-006 | Health Check API（Liveness / Readiness / Provider Health）を提供すること | - | - | 未実装 |
| FR-SEC-001 | Provider Credential（API Key等）をSecret Storeで暗号化管理し、平文をログ・応答に出力しないこと | - | - | 未実装 |
| FR-SEC-002 | Credential Rotation（多世代保持、検証付き切替、自動/手動）を提供すること | apap.domain.model.vo.CredentialRef, apap.domain.service.provider.CredentialRotationService | CredentialRefTest, CredentialRotationServiceTest | 実装中 |
| FR-SEC-003 | Access Control（CIAP発行トークン検証、テナント分離、Capability/Model単位の利用権限）を行うこと | - | - | 未実装 |
| FR-SEC-004 | 転送時暗号化（TLS 1.3）、保存時暗号化（AES-256相当）を行うこと | - | - | 未実装 |
| FR-SEC-005 | Provider Isolation（Adapter毎の実行分離、あるProvider障害・脆弱性の他Provider波及防止）を行うこと | - | - | 未実装 |
| FR-SEC-006 | 監査要件（誰が・いつ・何を・どのProviderで、保持期間設定）を満たすこと | - | - | 未実装 |
| FR-SEC-007 | Prompt/Responseの機微情報マスキング（Audit保存時のPIIマスクポリシー）を提供すること | apap.observability.audit.MaskingStrategy（SPI）, apap.observability.audit.RegexMaskingStrategy（既定実装、docs/observability/masking.mdに限界を明記）, apap.observability.audit.AuditConfig（マスキング未設定での本文保存opt-inをガード） | RegexMaskingStrategyTest, AuditEngineTest | 実装中 |
| NFR-AVL-001 | サービス稼働率（APAP自体）: 99.95% / 月（24時間365日稼働） | - | - | 未実装（デプロイ・運用体制の整備が前提のため本フェーズ対象外） |
| NFR-AVL-002 | 単一Provider全断時のサービス継続: Fallbackにより機能継続（対象Capabilityに代替Providerが存在する場合） | apap.execution.fallback.FallbackEngine, apap.execution.circuitbreaker.CircuitBreaker（CB Open候補のスキップ） | FallbackEngineTest | 実装中 |
| NFR-AVL-003 | APAPノード障害時: ステートレス設計により他ノードへ即時フェイルオーバー、リクエスト損失なし（冪等キーで再実行可能） | apap.execution.IdempotencyGuard（同一(tenantId, idempotencyKey)の並行二重実行防止） | IdempotencyGuardTest | 部分実装（処理中の二重実行防止は実装済。ノード障害後の完了済リクエストの冪等リプレイはRequest Cache（P7、FR-EXE-005参照）待ち） |
| NFR-AVL-004 | 計画メンテナンス: ローリングアップデートにより無停止 | - | - | 未実装（デプロイ・運用体制の整備が前提のため本フェーズ対象外） |
| NFR-PRF-001 | APAP付加レイテンシ（Gateway受信〜Adapter送信、Provider処理時間除く）: p50 ≤ 15ms、p99 ≤ 50ms | - | - | 未実装 |
| NFR-PRF-002 | Streaming初回チャンク付加遅延: ≤ 30ms | - | - | 未実装 |
| NFR-PRF-003 | スループット: 1,000 req/s / ノード、水平スケールで線形拡張 | - | - | 未実装 |
| NFR-PRF-004 | Cache Hit時応答: p99 ≤ 20ms | - | - | 未実装 |
| NFR-PRF-005 | 同時Streaming接続: 10,000接続 / ノード | - | - | 未実装 |
| NFR-EXT-001 | 新Provider追加はAdapter Pluginの追加とAdmin API登録のみで完結し、APAPコアの変更・再デプロイを不要とする | apap.adapter.spi.ProviderAdapter, apap.adapter.spi.plugin.PluginManifest/PluginManifestParser/SemVerRange | MockProviderAdapterContractTest, PluginManifestParserTest, SemVerRangeTest | 実装中 |
| NFR-EXT-002 | 新Model追加はメタデータ登録のみで完結する | - | - | 未実装 |
| NFR-EXT-003 | 新Capability追加はCapabilityスキーマ登録 + 対応Adapter拡張のみで、既存Capability利用者へ無影響 | - | - | 未実装 |
| NFR-EXT-004 | Routing Policy / Retry戦略 / Cache実装 / 認証方式はSPIにより差替・追加可能 | - | - | 未実装 |
| NFR-EXT-005 | 水平スケール（Kubernetes HPA、CPU/接続数/キュー長ベース） | - | - | 未実装 |
| NFR-MNT-001 | Provider AdapterはProvider毎に独立モジュール（独立リポジトリ/独立バージョニング可）とする | adapters/adapter-mock（apap.adapter.mock.MockProviderAdapter）, apap.adapter.spi.plugin.SemVerRange, apap.adapter.spi.SpiSurface（ADR-0016: SPI公開面とバージョニング規約） | MockProviderAdapterContractTest, AdapterDependencyRuleTest, SpiSurfaceTest | 実装中 |
| NFR-MNT-002 | レイヤ間依存はClean Architectureの依存規則（外→内の一方向）に従う | - | - | 未実装 |
| NFR-MNT-003 | 全公開APIはバージョニング（URLパス /v1）し、後方互換を1メジャーバージョン維持する | - | - | 未実装 |
| NFR-MNT-004 | 設定は宣言的（GitOps可能なYAML/API）に管理し、変更履歴を保持する | - | - | 未実装 |
| NFR-OBS-001 | 全リクエストにRequest ID / Trace IDを付与し、ログ・メトリクス・トレースを相関可能とする | - | - | 未実装 |
| NFR-OBS-002 | メトリクスはOpenMetrics互換形式で公開する | apap.observability.metrics.OpenTelemetryMetricsRecorder（OpenTelemetry API経由。実際のOpenMetrics/Prometheus形式での公開は宿主が注入するSDKのExporter設定に依存、CLAUDE.md不変条件6） | MetricsEngineTest | 実装中（Exporter配線はGateway/宿主側の責務、本モジュールはAPI呼出までを担う） |
| NFR-OBS-003 | 構造化ログ（JSON）とし、機微情報を含めない | - | - | 未実装 |
| NFR-OBS-004 | SLO（可用性・レイテンシ）をメトリクスから算出可能とする | - | - | 未実装 |
| NFR-SEC-001 | Credentialは専用Secret Storeに保存し、メモリ上でも必要最小期間のみ保持する | - | - | 未実装 |
| NFR-SEC-002 | 全通信TLS 1.3、内部通信はmTLS | - | - | 未実装 |
| NFR-SEC-003 | 監査ログは追記専用ストレージへ保存し、既定保持期間は400日（設定可能） | - | - | 未実装 |
| NFR-SEC-004 | 最小権限原則（Adapterは自Provider Credentialのみアクセス可能） | apap.adapter.spi.SecretAccessor, apap.adapter.spi.SecretValue | SecretValueTest, MockProviderAdapterContractTest（credential leak検証） | 実装中 |
| NFR-DAT-001 | Conversation履歴の保持期間・削除API（テナントポリシーで設定、既定90日） | - | - | 未実装 |
| NFR-DAT-002 | Prompt/Response本文の保存は既定OFF（監査ポリシーで選択的に有効化、マスキング適用） | - | - | 未実装 |
| NFR-DAT-003 | イベントストアは追記専用、スナップショットにより再構築時間を制御 | apap.domain.port.EventStoreRepository（ADR-0014）, apap.domain.model.AggregateSnapshot | AggregateSnapshotTest | 実装中 |
