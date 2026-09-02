plugins {
    id("apap.library")
}

dependencies {
    // `api`（`implementation`ではなく）: ApapAdmin（ApapEngine.admin）がProvider/Model/ModelAlias/
    // RoutingPolicyやRegisterProviderCommand/RegisterModelCommand等をメソッドシグネチャに直接使う。
    // 埋込ホスト（prompt-engineがapap-runtimeのみに依存してApapAdminを呼ぶ想定、
    // docs/integration/prompt-engine.md参照）がこれらの型へコンパイル時に到達できる必要があるため。
    api(project(":modules:apap-domain"))
    api(project(":modules:apap-provider"))
    // `api`: ApapEngineのシグネチャ（ApapRequest/ApapResponse/ApapStreamChunk）と、
    // 実行系が投げる公開例外ApapExceptionが apap-api にあるため、埋込ホストから到達できる必要がある。
    api(project(":modules:apap-api"))
    implementation(project(":modules:apap-application"))
    implementation(project(":modules:apap-execution"))
    implementation(project(":modules:apap-routing"))
    implementation(project(":modules:apap-prompt"))
    implementation(project(":modules:apap-context"))
    implementation(project(":modules:apap-plugin"))
    implementation(project(":modules:apap-cache"))
    implementation(project(":modules:apap-cost"))
    implementation(project(":modules:apap-observability"))
    implementation(project(":modules:apap-infrastructure"))
    implementation(project(":modules:apap-adapter-spi"))
    // `api`: ApapEngineの公開APIが `suspend fun execute(...)` と
    // `fun executeStream(...): Flow<ApapStreamChunk>` を持つため、埋込ホストは
    // kotlinx-coroutinesの型（Flow/suspendの呼び出し）へコンパイル時に到達できる必要がある。
    // apap-adapter-spiが`AdapterStream.asFlow()`に対して同じ判断をしているのと同じ理由。
    // implementationにするとホスト側で「Unresolved reference: Flow」になる
    // （integration/host-compatのHostCompileClasspathTest/コンパイルで検出される）。
    api(libs.findLibrary("kotlinx-coroutines-core").get())
    // ResilientQueryEmbedder（ADR-0023）が縮退時にWARNログを出すために使う
    // （CLAUDE.md不変条件6でSLF4J APIは許可）。
    implementation(libs.findLibrary("slf4j-api").get())
    // CLAUDE.md不変条件6: apap-executionのTracerを埋込側から注入できるようDefaultExecutionEngine/
    // AttemptExecutorへ配線する（02_システム仕様.md 2.19 Span構成）。
    implementation(libs.findLibrary("opentelemetry-api").get())
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(project(":adapters:adapter-mock"))
    testImplementation(libs.findLibrary("opentelemetry-sdk").get())
    testImplementation(libs.findLibrary("opentelemetry-sdk-testing").get())
}
