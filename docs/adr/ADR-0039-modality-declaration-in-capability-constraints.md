# ADR-0039: 対応modalityを申告する手段がSPIに無い

- **ステータス**: Proposed（P15で検出。**実装は次フェーズ**）
- **関連要件**: FR-RTE-002（Capabilityに基づく候補選択）, FR-CAP-001
- **関連する設計書**: 03_基本設計.md 3.3.2（CapabilityConstraints）, 04_ドメイン設計.md 4.4（ContentPart）
- **検出**: P15 実Provider向けAdapter第1号の実装（docs/adapter-spi-findings.md §3.4）

## コンテキスト

`ContentPart` は text / image / audio / video / json の5種を持つが、
`CapabilityConstraints` には**どのmodalityを受け付けるかを申告するフィールドが無い**
（`maxInputTokens` / `maxOutputTokens` / `streamable` / `supportsTools` のみ）。

実Providerの Messages API は text と image を受け取るが audio / video の
content block を持たない。現状Adapterは:

- `capabilityConstraints().extra["modalities.input"] = "text,image"` という
  **自由文字列**で申告している（コアは解釈しない）
- 音声が来たら実行時に `UNSUPPORTED_CAPABILITY` で拒否している

そのため、音声を含むリクエストが音声非対応のProviderへルーティングされ、
**実行して初めて失敗する**。Routingは候補選択の時点でこれを避けられるはずである。

## 決定（案。次フェーズで確定させる）

`CapabilityConstraints` に対応modalityの集合を追加する。

```kotlin
data class CapabilityConstraints(
    // ... 既存
    val supportedInputModalities: Set<Modality> = emptySet(),  // 空=未申告（現状維持）
)
```

既定を「空＝未申告」にすれば既存Adapterは無改修で済み、ADR-0016のマイナー更新に収まる。
Routing側は、未申告のProviderを従来通り候補に残す（申告のあるものだけ絞り込む）。

## 影響

- `RoutingEngine` のハードフィルタに条件が1つ増える（`RoutingConstraints` ではなく
  リクエストの `ContentPart` 種別から必要modalityを導出する必要がある）
- 決めるまで、modality不一致は実行時エラーとして表面化し続ける
  （黙って落とすよりは良いが、Fallbackを1段消費する）
