plugins {
    id("apap.library")
}

// ADR-0025補遺: Redis/Lettuceという重量級の実行時依存を、apap-runtimeが無条件にtransitive依存する
// `apap-infrastructure`から分離する。埋込ホストがこのモジュールを明示的に依存追加し、
// `ExecutionEngineComposer`等のPort差替引数（CircuitBreakerStateStore/RateLimitCounterStore/
// CacheStore）へ渡した場合にのみ実行時クラスパスに乗る。In-Memory実装（既定）はこの依存を
// 一切要求しない（ADR-0001: 単一プロセス埋込利用ではIn-Memoryで十分）。
dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-cache"))
    implementation(libs.findLibrary("slf4j-api").get())
    implementation(libs.findLibrary("lettuce-core").get())
    // ADR-0017で確定済みのJSONスタック。CircuitBreakerState/TokenBucketStateの直列化に使う
    // （CacheStore<ByteArray>自体はCacheCodecが上位で直列化を担うため、ここでは使わない）。
    implementation(libs.findLibrary("jackson-databind").get())
    implementation(libs.findLibrary("jackson-module-kotlin").get())
    implementation(libs.findLibrary("jackson-datatype-jsr310").get())
    testImplementation(project(":modules:apap-testkit"))
}
