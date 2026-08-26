plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // NoOpQueryEmbedder/SummarizeCompactionStrategyが構築時ログを出すために使う
    // （CLAUDE.md不変条件6でSLF4J APIは許可）。
    implementation(libs.findLibrary("slf4j-api").get())
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("konsist").get())
    // ADR-0023: ContextManager.build/QueryEmbedder.embedがsuspendになったため、テストからの
    // 呼出にrunBlockingを使う。
    testImplementation(libs.findLibrary("kotlinx-coroutines-core").get())
}
