pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // gradle/libs.versions.toml はGradleの既定パスのため、`libs` カタログは自動生成される。
}

rootProject.name = "apap-engine"

includeBuild("build-logic")

include(
    "modules:apap-api",
    "modules:apap-domain",
    "modules:apap-adapter-spi",
    "modules:apap-application",
    "modules:apap-execution",
    "modules:apap-routing",
    "modules:apap-prompt",
    "modules:apap-context",
    "modules:apap-provider",
    "modules:apap-plugin",
    "modules:apap-cache",
    "modules:apap-cost",
    "modules:apap-observability",
    "modules:apap-infrastructure",
    "modules:apap-runtime",
    "modules:apap-testkit",
    "gateway:apap-gateway",
    "adapters:adapter-mock",
    "adapters:adapter-generic-http",
)
