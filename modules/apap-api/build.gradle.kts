plugins {
    id("apap.library")
}

dependencies {
    // ADR-0016と同種の判断: apap-apiはapap-runtimeの公開DTO/インターフェース面のみを持つ薄いモジュール
    // とし、埋込ホスト（prompt-engine等）がApapEngineBuilderで得たApapEngineをコード上で扱うために
    // 必要な最小限の依存（apap-domainのVO: ContentPart/Usage/Cost/CapabilityId/TenantId等）のみを持つ。
    // apap-execution/apap-provider等の内部配線には依存しない。
    // `api`（`implementation`ではなく）: ApapRequest/ApapResponse等がapap-domainのVOを公開シグネチャに
    // そのまま使うため、apap-apiの利用側（prompt-engine等）がそれらの型へ直接参照できる必要がある。
    api(project(":modules:apap-domain"))
}
