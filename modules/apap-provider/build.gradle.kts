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
}
