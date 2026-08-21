plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    // VALIDATING処理でProviderAdapter/PluginManifest型を直接扱うために必要（ADR-0015の対象=adapters/*
    // ではないため、apap-domain以外への依存を禁じる制約はここには適用されない）。
    implementation(project(":modules:apap-adapter-spi"))
    // CapabilityRegistryのJSON Schema検証（テナント入力境界）。自前実装はせず、公式JSON Schema
    // Test Suiteで検証済みのライブラリに委譲する。
    implementation(libs.findLibrary("json-schema-validator").get())
    testImplementation(project(":modules:apap-testkit"))
    testImplementation(libs.findLibrary("konsist").get())

    // ADR-0017: json-schema-validatorが推移的に要求するjackson-databind（既定2.18.3）を、
    // 埋込先prompt-engineが実際に使用するバージョンへ明示的に揃える。apap-provider自体は
    // Jackson APIを直接呼ばないため、依存を追加するのではなくバージョン解決のみを制約する。
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
