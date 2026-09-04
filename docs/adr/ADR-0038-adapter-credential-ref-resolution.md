# ADR-0038: AdapterがどのCredentialRefを使うべきかをSPIが伝えていない

- **ステータス**: Proposed（P15で検出。**実装は次フェーズ**）
- **関連要件**: FR-SEC-001, FR-SEC-002（Credential Rotation）
- **関連する設計書**: 03_基本設計.md 3.3.2, 09_状態遷移図.md（Credential 4状態）
- **検出**: P15 実Provider向けAdapter第1号の実装（docs/adapter-spi-findings.md §3.3）

## コンテキスト

`SecretAccessor.resolve(ref)` は `CredentialRef` を要求するが、Adapterはそれを受け取る口を持たない。

- `AdapterConfig`（`initialize`の引数）は `providerId` / `endpoints` / `rateLimits` /
  `regions` / `options` のみで、`CredentialRef` を持たない
- `authenticate()` は引数を取らない
- `validateCredential(ref)` だけが `CredentialRef` を受け取る（＝検証専用の経路にしか無い）

`Provider` アグリゲートは `credentialRefs` を保持しているにもかかわらず、Adapterへ渡っていない。
`adapter-mock` が固定のダミー参照を自前で持ち、そのKDocで
「実Providerと違い、AdapterConfigはどのCredentialRefを使うかを明示しない」と
書いていたのは、この欠落の既知の兆候だった。

実Adapterでは `AdapterConfig.options["credential.ref"]` という**型のない文字列**で
回避したが、これではRotation中に ACTIVE / STANDBY のどちらを使うべきかを表現できない
（ADR-0008の4状態モデルはVersionを持つが、`options` の文字列1本では版を切り替えられない）。

## 決定（案。次フェーズで確定させる）

- **案A**: `AdapterConfig` に `credentialRefs: List<CredentialRef>` を追加する。
  Adapterは `state == ACTIVE` のものを選ぶ。Rotationはコアが `initialize` をやり直すか、
  設定更新を通知する経路が別途要る
- **案B**: `authenticate(ref: CredentialRef): AuthContext` へ変更し、呼出ごとにコアが渡す。
  ADR-0016のSPI公開面の変更にあたり、`apap-adapter-spi` のメジャー更新を要する

案Bはローテーションの制御をコアに寄せられる点で筋が良いが、SPIの破壊的変更になる。

## 影響

- 解決すれば `fetchUsage` / `fetchCost` も実装可能になる（管理用の別Credentialを
  参照できるようになるため。現状は呼べず `null` を返している）
- 案Bを採る場合、既存Adapter（adapter-mock）の修正とSPIメジャーバージョン更新が要る（ADR-0016）
