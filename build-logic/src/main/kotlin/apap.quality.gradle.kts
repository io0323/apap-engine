plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.jetbrains.kotlinx.kover")
}

detekt {
    buildUponDefaultConfig = true
    autoCorrect = false
}

// modules/apap-domain と modules/apap-application は行カバレッジ80%未満でビルド失敗させる
// (ドメイン設計スケルトン構築タスクの要求)。他モジュールはレポート生成のみで閾値強制はしない。
val coverageEnforcedModules = setOf("apap-domain", "apap-application")

if (project.name in coverageEnforcedModules) {
    kover {
        reports {
            verify {
                rule("apap.quality.line-coverage-80") {
                    minBound(80)
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn("koverVerify")
    }
}
