import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // CLAUDE.md不変条件9（ガードは落ちることを確認して初めて完成）の実装のひとつ。
    // JUnitは「@Testが付いているが実行可能な形になっていないメソッド」——典型的には
    // Kotlinの式本体で戻り値型がUnitでなくなったテスト——を、既定では発見時の警告
    // （DiscoveryIssue: WARNING）として報告するだけで、実行せずビルドも緑のままにする。
    // 実際にこれで3件のテストが一度も実行されていなかった（TestMethodReturnTypeTest参照）。
    // criticalしきい値をWARNINGまで下げ、この種の発見時問題をビルド失敗として扱う。
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
}

dependencies {
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}
