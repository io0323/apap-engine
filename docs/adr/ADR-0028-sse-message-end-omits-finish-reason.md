# ADR-0028: SSEの`message_end`は`finish_reason`を省略する（エンジンが終了理由を伝播していないため）

## ステータス

Accepted（2026-09-02）

## コンテキスト

`docs/design/13_API設計.md` 13.3のSSE例は、ストリーム終端を次の形で示している。

```
event: message_end
data: {"finish_reason":"completed"}
```

しかし現時点の実装では、Gatewayはこの`finish_reason`を知る手段を持たない。

- `apap.execution.streaming.StreamingEngine`は終端時に
  `StreamChunk(type = StreamChunkType.MESSAGE_END, index = index++)` を送っており、
  **終了理由を載せていない**。
- `StreamChunk`（`apap-domain`）にも、公開型`ApapStreamChunk`（`apap-api`）にも
  `finishReason`フィールドが存在しない。
- 非Streamingの`CanonicalResponse`は`finishReason: FinishReason`を持つため、
  この欠落はStreaming経路に固有のものである。

## 決定

**`finish_reason`を固定値で埋めず、フィールドごと省略する。**
`message_end`のdataは`{}`とする。

`"completed"`を常に入れる案は採らない。`LENGTH_LIMIT`（トークン上限で打ち切り）や
`CONTENT_FILTERED`で終わったストリームに対しても`completed`と報告することになり、
クライアントは「正常に完了した」と誤って判断する。これは本リポジトリで繰り返し問題になった
「シグナルの不在を成功と読む」形そのもの（CLAUDE.md不変条件9）であり、
欠落しているより有害である。

省略であれば、クライアントは「値が無い＝理由不明」と正しく扱える。

## 影響（Consequences）

- **制約**: 13.3のSSE例と実装に差分が残る。この差分を知らずに`finish_reason`必須で
  実装したクライアントは動かない。`docs/openapi/apap-v1.yaml`の`message_end`スキーマでも
  `finish_reason`をrequiredにしない。
- **解消方法（推奨）**: `StreamChunk`/`ApapStreamChunk`に`finishReason: FinishReason?`を追加し、
  `StreamingEngine`が終端時に設定する。Adapterから終了理由が得られない場合に何を入れるかは
  別途決める必要がある（不明を`null`のままにするか、`COMPLETED`とみなすか）。
  この判断はAdapter SPIの`AdapterChunk`が終了理由を運べるかにも依存するため、本ADRでは決めない。
- **見直す条件**: 上記が実装された時点で本ADRをSupersedeし、`message_end`に`finish_reason`を載せる。
- **関連**: `docs/design/02_システム仕様.md` 2.10（Streaming Flow）、FR-CAP-004、
  CLAUDE.md不変条件9。
