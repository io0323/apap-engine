# ADR-0031: CanonicalRequestが13.2の入力表現（role / Template参照）を取りこぼしている

- **ステータス**: Accepted
- **関連要件**: FR-CAP-001（Chat: Multi Turn / System Prompt / User Prompt）, FR-PMT-004（Prompt Template）
- **関連する設計書**: 13_API設計.md 13.2（リクエスト形式）, 03_基本設計.md 3.3.1（CanonicalRequest）, 02_システム仕様.md 2.7（Prompt Engine）
- **検出**: P11 総合検証（P11-D1）

## コンテキスト

13_API設計.md 13.2 のリクエスト形式は、Chatの入力を

```
messages: [ { role, content: [ContentPart...] }, ... ]
```

と定義している。`role` は 04_ドメイン設計.md 4.3.4 の `TurnRole`（system / user / assistant / tool）と
同じ概念であり、System PromptとUser Promptの区別はこの `role` によってのみ表現される。

一方 03_基本設計.md 3.3.1 の `CanonicalRequest` は入力を

```
input: ContentPart[]
```

と定義しており、**roleを持たない**。両者の対応が設計書上どこにも書かれていない。

同様に、2.7が定めるPrompt PipelineのRendering段は `PromptTemplate` を描画する段だが、
`CanonicalRequest` には「どのTemplateを描画するか」を指す参照フィールドが無い。

実装は設計書の型定義に忠実に従った結果、次の状態になっている。

- Gatewayの `ChatRequestDto.toApapRequest` は `messages.flatMap { it.content }` で
  全メッセージのContentPartを平坦化し、**`role` を捨てている**（`apap.gateway.dto.ExecutionDto`）。
  同じ関数が未対応の `ContentPart.type` については `INVALID_REQUEST` で明示的に弾いており、
  「黙って無視するとプロンプトの一部が落ちたまま実行される」とKDocに書いてあるにもかかわらず、
  `role` については黙って落としている。
- `PromptPipeline` の3段目 `RenderingStage` は、参照フィールドが無いため
  意図的なパススルー実装になっている（そのKDocに理由が明記されている）。
  結果として `PromptTemplateManager` / `TemplateRenderEngine` は実装・テスト済みでありながら
  実行経路から一度も呼ばれない。

これは実装判断の誤りではなく、**設計書のリクエストモデルが13.2の表現力を持っていない**ことに起因する。
CLAUDE.md 不変条件8の判断基準（その解釈によって満たせなくなる要件IDが存在するか）に照らすと、
FR-CAP-001 の「System Prompt」と FR-PMT-004 が現状満たせないため、ADRを起票する。

## 決定

`CanonicalRequest`（および公開APIの `ApapRequest`）の入力表現を、roleを保持する形へ拡張する。
本ADRでは**方針のみ**を決定し、実装はP12以降のタスクとする（P11はコード追加より不足の発見を優先するため）。

1. **role の保持**: `input: List<ContentPart>` を `List<InputMessage>` 相当（`role: TurnRole` +
   `content: List<ContentPart>`）へ拡張する。`TurnRole` は 04_ドメイン設計.md 4.3.4 で既に定義済みの
   enumを再利用し、新しいrole概念を作らない（14章・9章の用語と一致させる）。
2. **移行期の互換**: 既存の `List<ContentPart>` を受ける経路は「全体が単一のUSERメッセージ」と
   等価な糖衣として残す。埋込ホスト（prompt-engine）が既に `ApapRequest.input` を使っているため、
   破壊的変更を一度に強制しない。
3. **Template参照**: `CanonicalRequest` に `promptTemplateRef`（テンプレートIDとバージョン、任意）を
   追加し、`RenderingStage` がこれを見て `PromptTemplateManager` を引く。参照が無ければ現状どおり
   パススルーとする（テンプレート未使用のリクエストを壊さない）。
4. **暫定の防御**: 拡張が入るまでの間、Gatewayは `role` を黙って捨てるのをやめ、
   `role` が `user` 以外のメッセージを含むリクエストを `INVALID_REQUEST` で明示的に拒否する。
   「送ったのに効かない」より「受け付けない」ほうが安全であり、同ファイルが `type` に対して
   既に採っている方針と一貫する。

## 影響（Consequences）

- FR-CAP-001は、4の暫定対応を入れた時点で「System Promptは受け付けない」ことが利用側に明示される。
  1〜3の実装完了までは充足しない。
- FR-PMT-004は3の実装完了まで充足しない。それまで `PromptTemplateManager` /
  `TemplateRenderEngine` は「実装済みだが実行経路から到達しない」状態が続く。
  この状態を「実装済」と報告しないことが重要（requirements-matrix.mdの判定基準を参照）。
- 1は `apap-api` の公開型の変更であり、埋込ホストへの影響がある。2の互換措置により
  ソース互換は保てるが、`apap-runtime` のバージョニング上はマイナー更新として扱う。
- SPI（`apap-adapter-spi`）の `AdapterRequest` にもroleを伝える必要があるかは、
  Adapter実装がProvider固有形式へ変換する際にroleを要することから「必要」と見込まれる。
  その場合はADR-0016の規約に従い `apap-adapter-spi` のメジャーバージョン更新を要する。

## 未決定のまま残る事項

- `tool` roleの扱い（FR-CAP-005のtool_results往復と同時に設計する必要がある）。
- Template参照をリクエスト単位で渡すのか、Policy（テナント設定）側で解決するのか。
  13.2にはリクエスト側のフィールドが無いため、後者の可能性も残る。
