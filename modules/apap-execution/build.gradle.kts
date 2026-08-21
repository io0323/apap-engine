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
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(project(":adapters:adapter-mock"))
    testImplementation(libs.findLibrary("konsist").get())
}
