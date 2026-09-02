plugins {
    id("apap.application")
}

application {
    mainClass.set("apap.gateway.MainKt")
}

// 型安全なVersion Catalogアクセサ（libs.ktor.serverCore）はPrecompiled Script Plugin適用下では
// 使えないため、build-logic/src/main/kotlin/Libs.kt の `Project.libs` 経由で参照する
// （他モジュールと同じ書き方に揃える）。
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    // Gatewayは apap-runtime（埋込ファサード）を内包し、HTTP層は薄いアダプタに徹する。
    // ビジネスロジックはすべてApapEngine/ApapAdmin側にあり、ここには置かない。
    implementation(project(":modules:apap-runtime"))
    implementation(project(":modules:apap-api"))

    implementation(lib("ktor-server-core"))
    implementation(lib("ktor-server-netty"))
    implementation(lib("ktor-server-content-negotiation"))
    implementation(lib("ktor-serialization-jackson"))
    implementation(lib("ktor-server-status-pages"))
    implementation(lib("ktor-server-call-id"))

    implementation(lib("jackson-databind"))
    implementation(lib("jackson-module-kotlin"))
    implementation(lib("jackson-datatype-jsr310"))

    // ADR-0004: CIAP発行JWTの検証。TokenVerifier interfaceの背後にのみ現れる。
    implementation(lib("java-jwt"))
    implementation(lib("jwks-rsa"))

    implementation(lib("slf4j-api"))
    implementation(lib("kotlinx-coroutines-core"))

    // /metrics（OpenMetrics）の実体。apap-runtimeはOpenTelemetry APIのみに依存し、
    // SDKは宿主が注入する契約（CLAUDE.md不変条件6）なので、宿主であるGatewayがSDKを持つ。
    implementation(lib("opentelemetry-api"))
    implementation(lib("opentelemetry-sdk"))

    // 実Providerが未配置でもGatewayを起動・E2E検証できるようにするための既定AdapterRegistry。
    runtimeOnly(project(":adapters:adapter-mock"))

    testImplementation(lib("ktor-server-test-host"))
    testImplementation(lib("ktor-client-content-negotiation"))
    // GracefulShutdownTestは実サーバへ本物のHTTPクライアントで接続する（testApplicationのハーネスでは
    // 停止時の挙動を検証できないため）。
    testImplementation(lib("ktor-client-cio"))
    testImplementation(project(":adapters:adapter-mock"))
    // adapter-mockのシグネチャに現れるSPI型（AdapterConfig/SecretAccessor/PluginManifest等）を
    // テストから直接組み立てるため。本番コードはSPIに触れない（HTTP層はApapEngineだけを見る）。
    testImplementation(project(":modules:apap-adapter-spi"))
    testImplementation(lib("kotlinx-coroutines-test"))
}
