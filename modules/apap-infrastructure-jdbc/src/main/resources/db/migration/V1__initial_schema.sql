-- docs/design/12_ER図.md をそのままPostgreSQL方言へ落としたDDL。
-- 実際の埋込ホスト（prompt-engine）がFlyway + PostgreSQLを既に使用しているため、
-- 同じ技術で実装する（ADR-0025）。JSON列はPostgreSQLのJSONB（GIN索引・演算子対応）を使う。
-- TIMESTAMP列は全てTIMESTAMPTZ（java.time.Instantとタイムゾーンの取り違えを構造的に避ける）。
--
-- ES印（EventStore併用）のAggregate（PROVIDER/MODEL/MODEL_ALIAS/ROUTING_POLICY/BATCH_JOB）の
-- テーブルは「最新状態のスナップショット/Read Model」であり、真の永続化はEVENT_STOREへの
-- 追記（(stream_id, version)の楽観ロック、ADR-0014のスナップショット機構も併用）。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE plugin (
    plugin_id VARCHAR(26) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(20) NOT NULL,
    spi_version VARCHAR(20) NOT NULL,
    signature VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE provider (
    provider_id VARCHAR(26) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    adapter_plugin_id VARCHAR(26) NOT NULL REFERENCES plugin (plugin_id),
    spi_version VARCHAR(20) NOT NULL,
    auth_type VARCHAR(40) NOT NULL,
    priority SMALLINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    rate_limit_rpm INT NOT NULL,
    rate_limit_tpm INT NOT NULL,
    rate_limit_concurrent INT NOT NULL,
    regions JSONB NOT NULL DEFAULT '[]',
    tags JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE provider_endpoint (
    endpoint_id VARCHAR(26) PRIMARY KEY,
    provider_id VARCHAR(26) NOT NULL REFERENCES provider (provider_id),
    region VARCHAR(20) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    weight SMALLINT NOT NULL
);

CREATE INDEX idx_provider_endpoint_provider_id ON provider_endpoint (provider_id);

CREATE TABLE credential_ref (
    credential_ref_id VARCHAR(26) PRIMARY KEY,
    provider_id VARCHAR(26) NOT NULL REFERENCES provider (provider_id),
    secret_ref VARCHAR(200) NOT NULL,
    version INT NOT NULL,
    state VARCHAR(20) NOT NULL,
    rotated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_credential_ref_provider_id ON credential_ref (provider_id);

CREATE TABLE model (
    model_id VARCHAR(26) PRIMARY KEY,
    provider_id VARCHAR(26) NOT NULL REFERENCES provider (provider_id),
    model_name VARCHAR(100) NOT NULL,
    version VARCHAR(40) NOT NULL,
    context_window INT NOT NULL,
    max_output_tokens INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    priority SMALLINT NOT NULL,
    regions JSONB,
    UNIQUE (provider_id, model_name, version)
);

CREATE INDEX idx_model_provider_id ON model (provider_id);

CREATE TABLE capability (
    capability_id VARCHAR(40) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    input_schema JSONB NOT NULL,
    output_schema JSONB NOT NULL,
    streamable BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE model_capability (
    model_id VARCHAR(26) NOT NULL REFERENCES model (model_id),
    capability_id VARCHAR(40) NOT NULL REFERENCES capability (capability_id),
    constraints JSONB,
    PRIMARY KEY (model_id, capability_id)
);

CREATE TABLE model_alias (
    alias_id VARCHAR(26) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(26) NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE alias_target (
    alias_id VARCHAR(26) NOT NULL REFERENCES model_alias (alias_id),
    model_id VARCHAR(26) NOT NULL REFERENCES model (model_id),
    weight SMALLINT NOT NULL,
    PRIMARY KEY (alias_id, model_id)
);

CREATE INDEX idx_alias_target_model_id ON alias_target (model_id);

CREATE TABLE price_entry (
    price_entry_id VARCHAR(26) PRIMARY KEY,
    model_id VARCHAR(26) NOT NULL REFERENCES model (model_id),
    input_per_1k DECIMAL(12, 6) NOT NULL,
    output_per_1k DECIMAL(12, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    -- 12章はeffective_from（単一時点）のみを規定するが、apap-domainのPriceEntryは
    -- 重複検証のためPeriod（from/to）を持つ（KDoc参照）。effective_toも併せて永続化する
    -- （12章からの意図的な拡張、要件充足に影響しない実装判断のためADR化せずここに根拠を記す）。
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_price_entry_model_id ON price_entry (model_id);

CREATE TABLE session (
    session_id VARCHAR(26) PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    principal VARCHAR(200) NOT NULL,
    attributes JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE conversation (
    conversation_id VARCHAR(26) PRIMARY KEY,
    session_id VARCHAR(26) NOT NULL REFERENCES session (session_id),
    tenant_id VARCHAR(26) NOT NULL,
    title VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    turn_count INT NOT NULL
);

CREATE INDEX idx_conversation_session_id ON conversation (session_id);

CREATE TABLE turn (
    turn_id VARCHAR(26) PRIMARY KEY,
    conversation_id VARCHAR(26) NOT NULL REFERENCES conversation (conversation_id),
    seq INT NOT NULL,
    role VARCHAR(10) NOT NULL,
    content_parts JSONB NOT NULL,
    model_id VARCHAR(26),
    usage JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, seq)
);

CREATE TABLE memory (
    memory_id VARCHAR(26) PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    -- ADR-0001: Vector Storeは当面RDBMS拡張（pgvector）で実装する。次元数はモデル依存のため
    -- 固定せず可変長vector型とする。
    embedding vector NOT NULL,
    importance FLOAT NOT NULL,
    ttl_at TIMESTAMPTZ,
    last_accessed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_tenant_scope ON memory (tenant_id, scope);

CREATE TABLE routing_policy (
    policy_id VARCHAR(26) PRIMARY KEY,
    scope VARCHAR(20) NOT NULL,
    tenant_id VARCHAR(26),
    workflow_id VARCHAR(100),
    rules JSONB NOT NULL,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE quota_policy (
    quota_id VARCHAR(26) PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    period VARCHAR(20) NOT NULL,
    limit_requests BIGINT,
    limit_tokens BIGINT,
    limit_cost DECIMAL(14, 4)
);

CREATE INDEX idx_quota_policy_tenant_id ON quota_policy (tenant_id);

CREATE TABLE budget (
    budget_id VARCHAR(26) PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    period VARCHAR(20) NOT NULL,
    limit_amount DECIMAL(14, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    consumed DECIMAL(14, 4) NOT NULL,
    thresholds JSONB NOT NULL,
    -- 12章はcurrent_window自体を規定しないが、apap-domainのBudgetは期間リセット判定のため
    -- Period（from/to）を持つ。ADR化せずここに根拠を記す（price_entryと同じ拡張パターン）。
    window_from TIMESTAMPTZ NOT NULL,
    window_to TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_budget_tenant_id ON budget (tenant_id);

CREATE TABLE prompt_template (
    template_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    version INT NOT NULL,
    body TEXT NOT NULL,
    variables JSONB NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL
);

CREATE INDEX idx_prompt_template_name ON prompt_template (name);

CREATE TABLE tenant_entitlement (
    tenant_id VARCHAR(26) NOT NULL,
    capability_id VARCHAR(40) NOT NULL,
    model_id VARCHAR(26) NOT NULL,
    permitted BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, capability_id, model_id)
);

CREATE TABLE health_latency_outcome (
    id BIGSERIAL PRIMARY KEY,
    provider_id VARCHAR(26) NOT NULL,
    model_id VARCHAR(26) NOT NULL,
    success BOOLEAN NOT NULL,
    latency_ms BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_health_latency_outcome_lookup ON health_latency_outcome (provider_id, model_id, occurred_at);

CREATE TABLE quota_snapshot (
    tenant_id VARCHAR(26) NOT NULL,
    provider_id VARCHAR(26) NOT NULL,
    model_id VARCHAR(26) NOT NULL,
    remaining INT NOT NULL,
    PRIMARY KEY (tenant_id, provider_id, model_id)
);

CREATE TABLE usage_record (
    usage_id VARCHAR(26) PRIMARY KEY,
    request_id VARCHAR(26) NOT NULL,
    tenant_id VARCHAR(26) NOT NULL,
    capability_id VARCHAR(40) NOT NULL,
    provider_id VARCHAR(26) NOT NULL REFERENCES provider (provider_id),
    model_id VARCHAR(26) NOT NULL REFERENCES model (model_id),
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    cost_amount DECIMAL(12, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    duration_ms INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_usage_record_tenant_occurred ON usage_record (tenant_id, occurred_at);

CREATE TABLE audit_record (
    audit_id VARCHAR(26) PRIMARY KEY,
    request_id VARCHAR(26) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(26) NOT NULL,
    principal VARCHAR(200) NOT NULL,
    capability_id VARCHAR(40) NOT NULL,
    model_alias VARCHAR(100),
    provider_id VARCHAR(26),
    model_id VARCHAR(26),
    routing_decision JSONB NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    response_digest VARCHAR(64),
    -- 追記専用（12章の設計注記）。UPDATE/DELETE権限はアプリケーションDBロールへ付与しないこと
    -- （権限管理そのものはデプロイ時の運用、本DDLの範囲外）。
    request_body TEXT,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(60),
    usage JSONB NOT NULL,
    cost JSONB NOT NULL,
    duration_ms INT NOT NULL,
    retries SMALLINT NOT NULL,
    fallbacks SMALLINT NOT NULL,
    conversation_id VARCHAR(26),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_record_search ON audit_record (tenant_id, occurred_at, provider_id, error_code);
CREATE INDEX idx_audit_record_request_id ON audit_record (request_id);

CREATE TABLE batch_job (
    job_id VARCHAR(26) PRIMARY KEY,
    tenant_id VARCHAR(26) NOT NULL,
    target_capability VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_items INT NOT NULL,
    completed_items INT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE batch_item (
    item_id VARCHAR(26) PRIMARY KEY,
    job_id VARCHAR(26) NOT NULL REFERENCES batch_job (job_id),
    seq INT NOT NULL,
    request_payload JSONB NOT NULL,
    result_payload JSONB,
    status VARCHAR(20) NOT NULL
);

CREATE INDEX idx_batch_item_job_id ON batch_item (job_id);

-- (stream_id, version)の楽観ロックで追記整合を担保（12章の設計注記、ADR-0014）。
CREATE TABLE event_store (
    stream_id VARCHAR(26) NOT NULL,
    version BIGINT NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (stream_id, version)
);

-- ADR-0014: EventStoreRepositoryのsnapshot機構（latestVersionとは別関心事）。
-- スナップショット本体（Aggregate状態）はInfrastructure層のシリアライズ方式（Jackson JSON、
-- ADR-0017で確定済みのJSONスタック）に委ねる。
CREATE TABLE event_store_snapshot (
    stream_id VARCHAR(26) PRIMARY KEY,
    version BIGINT NOT NULL,
    state_type VARCHAR(200) NOT NULL,
    state JSONB NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL
);
