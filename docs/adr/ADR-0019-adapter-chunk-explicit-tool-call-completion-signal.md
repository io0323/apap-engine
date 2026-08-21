# ADR-0019: AdapterChunkにToolCallデルタの明示的完了シグナルを追加する

## ステータス

Accepted（2026-08-22）

## コンテキスト

02_システム仕様.md 2.10「ToolCallデルタはStreaming Engineが組立て、完結時に確定イベント」の
実装（`apap.execution.streaming.ToolCallAssembler`）は、分割到着した`arguments`（JSON文字列断片）
を`callId`単位で連結し、蓄積した文字列の中括弧の対応関係（文字列リテラル内はエスケープ処理込みで
除外）が取れた時点で「完結」とみなすヒューリスティックだった。

これはP6着手前レビューで指摘された。中括弧の対応関係チェック自体は文字列リテラル内の`{`/`}`を
正しく除外できていたが、より根本的な設計上の問題として: **JSON構文の完結性を「Providerがこの
tool callの送信を終えたか」の代理指標として使うこと自体が誤り**である。Providerのストリームは
通常、tool call呼出の境界（どのデルタが最後の断片か）を明示的に通知する。コア側が構文完結性から
それを**推測**するのは、本来Adapterが知っている事実（Provider APIのネイティブなストリームイベント
に含まれる）を、Adapter層とコア層の境界を越えて再構築しようとする設計であり、次のような
コーナーケースで誤動作しうる:

- 単一tool callの引数が構文的に完結する断片の後、Providerが（本来なら別のtool callやテキストの
  はずが）実装バグ等で追加の断片を同じ`callId`へ送ってくる場合、コア側はすでに「完結」と判断して
  しまっているため後続の断片を正しく扱えない
- 完結判定を厳密なJSON構文解析ではなく軽量な中括弧カウントに頼っているため、将来Provider側の
  ストリーム形式が変わった際の耐性が低い

## 決定

`apap.adapter.spi.AdapterChunk`に、Adapterが明示的に設定できる`toolCallComplete: Boolean = false`
フィールドを追加する（オプショナル、ADR-0010と同様の後方互換パターン——Adapter側の対応は必須
ではなく、未対応Adapterは常にfalseのまま=デフォルト値を使い続けられる）。

- Adapterがこのフィールドを`true`に設定した場合、`ToolCallAssembler`はその時点で断片の連結を
  完了とみなし、中括弧バランスのチェックを行わずに確定させる（Provider側の明示的なシグナルを
  無条件に信頼する）
- Adapterがこのフィールドを設定しない（常にfalse）場合、`ToolCallAssembler`は既存の中括弧バランス
  ヒューリスティックへフォールバックする（第二候補、レビューコメント原文の「フォールバック実装」）
- 両者は排他ではなく「明示シグナル OR 中括弧バランス」のOR条件で完結判定する。準拠Adapterが最終
  断片で両方の条件を同時に満たす（構文的にも完結し、かつtrueを設定する）通常運用では、どちらの
  条件が先に真になっても同じ結果になるため後方互換に安全側で追加できる

`apap-adapter-spi`のバージョニング（ADR-0016）: このフィールド追加はSPI公開面
（`apap.adapter.spi.SpiSurface`が管理するドメイン型再エクスポート一覧）には影響しない
（`AdapterChunk`はapap-adapter-spi自身の型でありドメイン型のtypealiasではないため）。
ADR-0010の前例に倣い、Adapter開発者向けにはマイナーバージョン相当の追加として扱う
（デフォルト値ありのオプショナルフィールド追加であり、既存Adapter実装の変更は不要）。

## 影響（Consequences）

- **制約**: `apap.adapter.mock.MockProviderAdapter`を含む既存Adapterは本フィールドを設定しない
  （常にfalse）ため、当面は中括弧バランスヒューリスティックが実質的な完結判定手段であり続ける。
  実Provider向けAdapterを実装する際は、Provider APIのネイティブなtool call境界イベントを
  `toolCallComplete=true`へマップすることが推奨される（`docs/design/15_Provider追加手順.md`への
  追記は本ADRの範囲外、別途Adapter開発ガイド更新時に反映すること）。
- **見直す条件**: 実Provider Adapterが実装され、`toolCallComplete`を設定するようになった時点で、
  中括弧バランスヒューリスティックを「非対応Adapter向けのフォールバックのみ」として明確に
  ドキュメント上も格下げする。
- **関連**: 02_システム仕様.md 2.10、`apap.execution.streaming.ToolCallAssembler`、
  ADR-0010（同種のオプショナルSPIメソッド追加の前例）、P6着手前レビュー（2026-08-22）。
