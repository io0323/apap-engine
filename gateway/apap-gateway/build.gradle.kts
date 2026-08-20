plugins {
    id("apap.application")
}

application {
    // 現時点では配線コード・Controller等は未実装（P1はモジュール骨格のみ）。
    // 起動エントリポイントの型のみ用意する。
    mainClass.set("apap.gateway.MainKt")
}

dependencies {
    implementation(project(":modules:apap-application"))
    implementation(project(":modules:apap-api"))
}
