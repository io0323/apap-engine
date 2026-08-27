plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-routing"))
    implementation(project(":modules:apap-prompt"))
    implementation(project(":modules:apap-context"))
    implementation(project(":modules:apap-cache"))
    implementation(project(":modules:apap-cost"))
    // AttemptExecutorがProviderAdapter/AdapterRequest/AdapterException/AdapterStreamを直接扱うために必要
    // （当初の依存宣言には含まれていなかった。02_システム仕様.md 2.8 step8のAdapter呼出はExecution
    // Engineの責務）。kotlinx-coroutines-coreはapap-adapter-spiがapiとして再エクスポートするため
    // 別途宣言しない。
    implementation(project(":modules:apap-adapter-spi"))
    // AdapterRegistry.resolve(pluginId)でCandidateからProviderAdapter実装を引くために必要。
    implementation(project(":modules:apap-provider"))
    implementation(libs.findLibrary("slf4j-api").get())
    // CLAUDE.md不変条件6: SLF4J API同様、OpenTelemetry APIまでは持ち込み可（SDKは宿主が注入する）。
    // 02_システム仕様.md 2.19 Span構成（gateway/prompt/routing/attempt[n]/mapping）の計装に使う。
    implementation(libs.findLibrary("opentelemetry-api").get())
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("opentelemetry-sdk").get())
    testImplementation(libs.findLibrary("opentelemetry-sdk-testing").get())
    testImplementation(project(":adapters:adapter-mock"))
    testImplementation(libs.findLibrary("konsist").get())
}
