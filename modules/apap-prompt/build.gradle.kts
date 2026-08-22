plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // DefaultPromptEngineが構築時ログを出すために使う（CLAUDE.md不変条件6でSLF4J APIは許可）。
    implementation(libs.findLibrary("slf4j-api").get())
    // PromptValidatorのoutputSchema構文検証（テナント入力境界）。apap-provider/CapabilityRegistryと
    // 同じ判断: 自前の簡易パーサ・簡易バリデータでは済ませない。
    implementation(libs.findLibrary("json-schema-validator").get())
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("konsist").get())

    // ADR-0017: json-schema-validatorが推移的に要求するjackson-databindを、埋込先prompt-engineが
    // 実際に使用するバージョンへ明示的に揃える（apap-provider/build.gradle.ktsと同じ制約）。
    constraints {
        implementation(libs.findLibrary("jackson-databind").get()) {
            val jacksonVersion = libs.findVersion("jackson").get()
            because(
                "prompt-engine（埋込先）がjackson-databind:${jacksonVersion}に依存しているため、" +
                    "json-schema-validatorの推移的要求より優先してこのバージョンへ揃える（ADR-0017）。",
            )
        }
    }
}
