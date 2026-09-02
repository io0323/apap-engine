# ADR-0030: Admin系リソースの「存在しない」に汎用の `RESOURCE_NOT_FOUND` を追加する

## ステータス

Accepted（2026-09-02）

## コンテキスト

P10のGateway実装で `GET /admin/v1/providers/{id}` が、存在しないProviderに対して
`CAPABILITY_NOT_AVAILABLE` を返していた。**これは誤りである。**

`docs/design/13_API設計.md` 13.4のエラーコード表にある404系は3つだけで、
いずれも**実行系API向けの意味**を持つ:

| code | 13.4の説明 |
|---|---|
| `CAPABILITY_NOT_AVAILABLE` | 対応候補なし（Capability未提供） |
| `ALIAS_NOT_FOUND` | Alias未定義 |
| `CONVERSATION_NOT_FOUND` | — |

エラーコードは公開契約であり、クライアントはこれで分岐する。
「Capabilityに対応する候補が無い」と「指定IDのリソースが存在しない」を同一コードで返すと、
クライアントは**Capability自体が使えない**と誤解する。前者はリトライやFallbackの設計に
影響する運用上の状態、後者は単なる参照ミスであり、意味がまったく違う。

根本原因は、13.4の表が実行系API中心で構成されており、13.1に定義されている
**Admin系APIのnot-foundを欠いている**ことにある。Provider/Model/Policy/Quota/Budget/Plugin
のいずれについても「そのIDは無い」を表すコードが無い。

## 決定

### 1. `RESOURCE_NOT_FOUND`（HTTP 404、`retryable=false`）を追加する

Admin系APIの全リソース（Provider / Model / Alias / Policy / Quota / Budget / Plugin）で、
「指定IDのリソースが存在しない」場合に使う。

### 2. 追加先はGateway側の `ApiError`。ドメインの `ErrorCode` には足さない

`apap.domain.model.vo.ErrorCode` は13.4の表の忠実な写しであり、同時に
**エンジンが実際に produce するコードの集合**でもある。エンジンはAdmin系リソースの
not-foundを発生させない（`ApapAdmin`は`null`を返すだけで、それをHTTPの404に写すのは
Gatewayの責務）。エンジンが決して出さないコードをドメイン列挙へ混ぜると、
その列挙が「13.4の写し」なのか「エンジンの出力集合」なのか曖昧になる。

ADR-0027の`NOT_IMPLEMENTED`と同じ扱いにし、`ApiErrorClosedSetTest`が
「ドメイン列挙は13.4と完全一致」「Gatewayの追加はADRで根拠を示した2つだけ」を機械検証する。

### 3. 既存の `ALIAS_NOT_FOUND` / `CONVERSATION_NOT_FOUND` は残す（統合しない）

理由:

- **13.4の表に既に載っており、公開契約である。** 削除・統合はクライアントの分岐を壊す
  破壊的変更になる。`docs/design/*.md` は編集しない方針でもあり、表から消すことはできない。
- **意味が具体的で有用。** `ALIAS_NOT_FOUND` は「Aliasが未定義」という、クライアントが
  設定を直せば解消する状態を名指しする。汎用コードへ丸めると、クライアントは
  `detail` の文字列を解析しないと何が無いのか判別できなくなる（文字列解析への依存は
  それ自体が壊れやすい契約になる）。

したがって **具体的なコードがある場合はそちらを優先し、無い場合に `RESOURCE_NOT_FOUND` を使う**
という使い分けにする。`GET /admin/v1/aliases/{name}` は引き続き `ALIAS_NOT_FOUND` を返す。

## 影響（Consequences）

- **制約**: 13.4に該当する具体的コードがあるリソースでは `RESOURCE_NOT_FOUND` を使わない。
  新たにAdmin系リソースを追加する場合、まず13.4に適合するコードがあるか確認すること。
- **クライアントへの影響**: `GET /admin/v1/providers/{id}` の404応答の `code` が
  `CAPABILITY_NOT_AVAILABLE` から `RESOURCE_NOT_FOUND` へ変わる。HTTPステータスは404のまま。
  Admin APIはまだ公開前のため、互換性の移行期間は設けない。
- **見直す条件**: 設計書13.4が改訂されてAdmin系のnot-foundが正式に定義された場合、
  本ADRをSupersedeし、ドメイン側`ErrorCode`へ移す。
- **関連**: ADR-0027（`NOT_IMPLEMENTED`、13.4への追加という同型の判断）、
  CLAUDE.md不変条件8（設計書を書き換えずADRで記録する）。
