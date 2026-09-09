# ADR-0043: Pluginロード時に、Adapter自身が申告するSPIバージョンを突き合わせる

- **ステータス**: Accepted（P18で決定・実装）
- **関連要件**: NFR-EXT-001（新Provider追加はPlugin追加のみで完結し、コアの変更・再デプロイを要しない）
- **関連する設計書**: 15_Provider追加手順.md 15.1 Step2-3, 09_状態遷移図.md 9.5（Plugin状態）
- **関連ADR**: ADR-0016（SPI semver）, ADR-0042（消費者のいないSPIメソッド）

## コンテキスト

ADR-0042で導入した「SPIメソッドと本番の消費者の対応表」により、
`ProviderAdapter.spiVersion()` に消費者がいないことが可視化された。

`PluginManager` は `plugin.yaml` の `spi_version` レンジだけを見てロード可否を決めていた。
しかし、

- `plugin.yaml` の `spi_version` は**人が書くメタデータ**
- `spiVersion()` は**コード自身の申告**（`SpiSurface.version` を返す実装が前提）

であり、**両者は独立に間違いうる**。SPI 1.1でビルドされたjarでも、マニフェストに
`>=2.0 <3.0` と書いてあればロードされ、最初の呼び出しで `NoSuchMethodError` になる。
Adapterがコアと独立にビルド・配布される（NFR-EXT-001）以上、
**マニフェストは申告であって証拠ではない**。

## 決定

`PluginManager` のロード時検証に2点を追加する。`ServiceLoader` でインスタンスを得た直後、
`initialize` の前に行う（`spiVersion()` はインスタンスメソッドのため、この位置が最初の機会）。

1. **(a) マニフェストとコードの突き合わせ**: `plugin.yaml` の `spi_version` レンジに
   `adapter.spiVersion()` が収まること
2. **(b) ホストとの互換**: `adapter.spiVersion()` がホストの `SpiSurface.version` と互換であること

不整合はQUARANTINEDとし、理由を載せて `PluginQuarantined` を発火する（既存の隔離経路と同じ）。
`spiVersion()` の呼び出し自体が例外・`Error` を投げた場合も隔離する
——Plugin側のコードを初めて呼ぶ地点であり、取りこぼすと**検証していないPluginをロード済みとして扱う**。

### ホスト互換は一方向である

(b) の判定は「メジャー一致」だけでは足りない。

| Adapterの申告 | ホスト | 判定 | 理由 |
|---|---|---|---|
| 2.0.0 | 2.1.0 | ロード可 | SPIは後方互換。古いマイナーで書かれたコードは新しいホストで動く |
| 2.1.0 | 2.0.0 | **隔離** | ホストに存在しないSPIメンバを参照しうる（実行時の`NoSuchMethodError`） |
| 1.1.0 | 2.0.0 | 隔離 | メジャー不一致 |

よって条件は **メジャー一致 かつ 申告 ≤ ホスト**。ドメインの `SemVer.isCompatibleWith`
（メジャー一致）はこの一方向性を表さないため、`PluginManager` 側で `<=` を重ねて表現する。
`isCompatibleWith` の意味（semverの一般則）は変えない。

### 記録する版

`PluginRegistration.spiVersion` にはホストの版ではなく**検証済みのPlugin自身の申告値**を記録する。
「そのPluginがどのSPIでビルドされたか」を持つのが自然であり、ホストの版はどのPluginでも同じで
情報を持たない。

## 影響

- `SpiSurface.adapterMethodConsumers` の `spiVersion` を「消費者あり」へ更新した。
  ADR-0042で可視化された未消費メソッドは5件から**4件**（`translateTools` / `discoverModels` /
  `fetchUsage` / `fetchCost`）になった
- `spiVersion()` を実装していない（＝SPIのデフォルトが無いので実装は必須）Adapterは存在しないが、
  **嘘の値を返すAdapterは隔離される**。`SpiSurface.version` をそのまま返す実装
  （`adapter-mock` / `adapter-anthropic`）が正しい形である
- テストのPluginフィクスチャは、マニフェスト・コード・ホストの3者が自己整合している必要がある。
  食い違いを作る検証には、申告値だけを固定した最小のPlugin（`FixedSpiVersionAdapter`）を使う
