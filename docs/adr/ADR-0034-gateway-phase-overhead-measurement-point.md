# ADR-0034: Gateway層に付加レイテンシの計測点を置く

- **ステータス**: Accepted
- **関連要件**: NFR-PRF-001（APAP付加レイテンシ p50≤15ms / p99≤50ms）, NFR-PRF-002（Streaming初回チャンク付加遅延 ≤30ms）, NFR-OBS-002
- **関連する設計書**: 01_要件定義.md NFR-PRF-001/002, 02_システム仕様.md 2.19（Monitoring仕様）
- **検出**: P11 総合検証（P11-D4）

## コンテキスト

NFR-PRF-001 は付加レイテンシの計測区間を「**Gateway受信〜Adapter送信**」と定義している。
02_システム仕様.md 2.19 のメトリクス表も、この指標に対応するメトリクスとして

```
| apap_overhead_duration_seconds | Histogram | phase(gateway/prompt/routing/mapping) |
```

を定義しており、`phase` ラベルの最初の値として `gateway` を挙げている。

しかし実装では、このメトリクスを記録する `PhaseTimings` が `ExecutionEngine.execute` の
**内側にしか存在しない**。実際に記録されるphaseは
`prompt` / `routing` / `context` / `token-estimate` / `cache-lookup` / `execution` / `mapping` の7種で、
`gateway` phaseは**一度も記録されない**。

その結果、次のGateway層の処理時間がどのメトリクスにも計上されていない。

- Bearerトークンの検証（JWKS参照を含む）
- リクエストJSONのデシリアライズとDTO→ドメイン変換
- Idempotency-Keyの判定
- レート制限判定
- 応答のドメイン→DTO変換とシリアライズ

さらに `execution` phaseはProvider呼び出しそのものを含むため、記録されている7phaseを
単純に合計しても「APAPの付加分」にはならない。つまり **NFR-PRF-001が定義する区間を
計測するメトリクスが存在しない**。

これは実装の抜けではなく、設計書に計測点が定義されていないことに起因する。2.19は
`phase(gateway/...)` というラベル値を挙げるだけで、Gateway層のどこからどこまでを
`gateway` phaseとするか、誰がそれを記録するかを述べていない。Gatewayは
「HTTP層の薄いアダプタに徹し、ビジネスロジックを置かない」制約下にあるため、
「Gatewayが計測してよいのか」自体が判断を要する。

CLAUDE.md 不変条件8の判断基準に照らすと、この未定義のままではNFR-PRF-001/002を
本番で監視できず要件を満たせないため、ADRを起票する。

## 決定

1. **Gatewayに`gateway` phaseの計測点を置く。** 計測はビジネスロジックではなく
   横断的関心事（既にGatewayが担っている`X-Request-Id`付与・メトリクス公開と同種）であり、
   「ビジネスロジックを置かない」制約に抵触しない。
2. 区間は「**リクエスト受信からApapEngineの呼び出し直前まで**」とする。
   Adapter送信までの残りはエンジン内部の既存phaseが覆っているため、
   `gateway` + `prompt` + `routing` + `context` + `token-estimate` + `cache-lookup` の合計が
   NFR-PRF-001の定義区間に一致する。`execution` はProvider呼び出しを含むため合計に入れない。
3. 応答側（ドメイン→DTO変換とシリアライズ）は `gateway-response` phaseとして別に記録する。
   往路と復路を同じラベルに混ぜると、NFR-PRF-001の区間だけを取り出せなくなる。
4. `MetricsRecorder` は既にPortとして`recordOverheadDuration(phase, seconds)`を持つため、
   SPI追加は不要。GatewayはApapEngine越しにこのPortへ到達できないので、
   `apapGateway(...)`の引数として`MetricsRecorder`を受け取る（既に`OpenMetricsRenderer`を
   受け取っているのと同じ形）。
5. `MetricsCoverageTest`（2.19のメトリクス名クローズドセット検証）に加え、
   **phaseラベルの集合**についても、`gateway`が実際に記録されることを検証するテストを置く。
   ラベル値は2.19上クローズドセットではない（実測phase名をそのまま使う方針）ため、
   「gatewayが含まれること」のみを検証する。

## 影響（Consequences）

- NFR-PRF-001/002 は、1〜5の実装完了までは「ベンチマークでは測れるが本番では監視できない」
  状態が続く。requirements-matrix.md では部分実装とし、この状態を実装済と報告しない。
- Gatewayの`apapGateway(...)`のシグネチャが1引数増える。既にパラメータが多く
  `@Suppress("LongParameterList")`を付けているため、設定オブジェクトへの集約を検討する余地がある
  （本ADRでは決定しない）。
- 埋込ホスト（Gatewayを使わずapap-runtimeを直接組み込む形態）では`gateway` phaseは存在しない。
  この場合NFR-PRF-001の「Gateway受信」に相当する時刻はホストの入口であり、
  APAPからは観測できない。`docs/integration/prompt-engine.md` に、
  ホスト側で入口時刻を計測して`MetricsRecorder`へ渡す方法を記載する。

## 未決定のまま残る事項

- ヒストグラムのバケット境界。NFR-PRF-001の閾値（15ms / 50ms）を跨ぐ分解能が必要だが、
  既定バケットで足りるかは実測後に判断する。
