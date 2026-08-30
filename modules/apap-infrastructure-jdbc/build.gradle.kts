plugins {
    id("apap.library")
}

// ADR-0025補遺: JDBC/PostgreSQL/Flywayという重量級の実行時依存を、apap-runtimeが無条件に
// transitive依存する`apap-infrastructure`から分離する。埋込ホスト（prompt-engine等）が
// このモジュールを明示的に依存追加し、`ExecutionEngineComposer`等のPort差替引数へ渡した場合
// にのみ実行時クラスパスに乗る。In-Memory実装（既定、`apap-infrastructure`側）は
// この重量級依存を一切要求しない。
dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(libs.findLibrary("slf4j-api").get())
    implementation(libs.findLibrary("flyway-core").get())
    implementation(libs.findLibrary("flyway-postgresql").get())
    runtimeOnly(libs.findLibrary("postgresql").get())
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("postgresql").get())
}
