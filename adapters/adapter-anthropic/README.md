# adapter-anthropic

実在するAI Provider（Messages API）向けの `ProviderAdapter` 実装。

このモジュールの目的はAdapterそのものではなく、**ProviderAdapter SPI が実APIとの接触に
耐えるかの検証**である。検証結果は [`docs/adapter-spi-findings.md`](../../docs/adapter-spi-findings.md) を参照。

## このモジュールだけが製品名を書いてよい

01_CLAUDE.md 不変条件1により、実Provider固有の知識（URL・認証方式・レスポンス形式・
エラーコード）は `adapters/` 配下にのみ置いてよい。`VendorNeutralityTest` は
`config/vendor-neutrality/vendor-specific-adapters.txt` に理由付きで登録された
ディレクトリだけを走査から外す。**このモジュールの外へ製品名を持ち出さないこと。**

## テストの構成

| テスト | 実APIを叩くか | 既定で実行されるか |
|---|---|---|
| `AnthropicAdapterContractTest` | いいえ（再生） | はい |
| `AnthropicAdapterReplayTest` | いいえ（再生） | はい |
| `RecordingProvenanceTest` | いいえ | はい |
| `LiveProviderTest` | **はい** | **いいえ**（環境変数で明示的に有効化） |

再生は `HttpTransport` を差し替えて行う。Adapter本体（ヘッダ組立・ボディ生成・SSE解析・
エラー分類）は実APIのときと**同じ経路**を通る。

## ⚠️ 現在のフィクスチャは実記録ではない

`src/test/resources/recordings/` の内容は**公開API仕様に基づく手書き**で、
実APIから記録したものではない。各ファイルの `source` フィールドがそれを宣言し、
`RecordingProvenanceTest` が全件に宣言があることを検証している。

そのため `docs/adapter-spi-findings.md` の **[要実測]** と印を付けた項目は未検証のまま。

## 実記録への差し替え手順

**鍵をこのリポジトリに置かないこと。** 環境変数で渡す。

```bash
export APAP_LIVE_PROVIDER_TEST=1
export APAP_PROVIDER_API_KEY='<あなたの鍵>'
export APAP_PROVIDER_MODEL='<実在するモデル名>'
export APAP_RECORD_DIR="$(mktemp -d)"

./gradlew :adapters:adapter-anthropic:test --tests '*LiveProviderTest*'
echo "$APAP_RECORD_DIR"
```

`APAP_RECORD_DIR` に往復の記録が出力される。`RecordingHttpTransport` が
リクエストヘッダを一切記録せず、応答の識別子フィールド（`id` など）を伏字にするが、
**完全ではない**。次の手順で確認してから配置すること。

1. 出力を**目で読む**。鍵・組織ID・メールアドレス・実利用者の入力が無いか確認する
2. `source` が `recorded from live API` になっていることを確認する
3. `src/test/resources/recordings/` へ配置する
4. `RecordingProvenanceTest` の
   `the fixtures are declared as hand-authored while no live recording exists` を更新する
   （このテストは、宣言と実態がずれたまま「実APIで検証済み」と読まれるのを防ぐための足かせ）
5. `docs/adapter-spi-findings.md` の §0 と **[要実測]** の各項目を再評価する

## 設定

`AdapterConfig.options` で受け取る項目:

| キー | 既定 | 用途 |
|---|---|---|
| `credential.ref` | `anthropic-api-key` | `SecretAccessor.resolve` に渡す参照名。SPIが参照を渡してくれないための回避（ADR-0038） |
| `credential.version` | `1` | 同上 |

`AdapterConfig.endpoints` は重み最大のものの `baseUrl` を使う。
