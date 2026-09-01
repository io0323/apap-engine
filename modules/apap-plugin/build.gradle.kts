plugins {
    id("apap.library")
}

dependencies {
    implementation(project(":modules:apap-domain"))
    implementation(project(":modules:apap-adapter-spi"))
    implementation(libs.findLibrary("slf4j-api").get())
    testImplementation(project(":modules:apap-testkit"))
}

// テストで「本物のjarファイルから分離URLClassLoaderでロードする」ことを検証するため、
// adapters:adapter-mockの実jarを使う。Task/Configuration等のライブオブジェクトを
// テストタスクのdoFirstクロージャへ持ち込むとconfiguration cacheのシリアライズに失敗するため
// （ビルドスクリプトのトップレベルval参照がスクリプトオブジェクト全体を暗黙キャプチャしてしまう）、
// タスクパスの文字列参照でdependsOnするに留め、実際のjarパスの探索はテスト側
// （`PluginManagerTest`、規約に基づく相対パス）に任せる。
tasks.test {
    dependsOn(":adapters:adapter-mock:jar")
}
