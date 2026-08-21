# ADR-0016: Adapter SPIのtypealias境界はソースレベル分離のみを提供する（SPI公開面の明示管理）

## ステータス

Accepted（2026-08-21）

## コンテキスト

`apap-adapter-spi`（`modules/apap-adapter-spi/src/main/kotlin/apap/adapter/spi/DomainAliases.kt`）は、
CLAUDE.md 不変条件2「`adapters/*`は`apap-adapter-spi`のみに依存する」をAdapter実装側から機械検証する
ため、`apap.domain.model.vo.CapabilityId`等のドメインVOを`apap.adapter.spi`名前空間の`typealias`として
再エクスポートしている。

Kotlinの`typealias`はコンパイル時のみの別名であり、生成されるバイトコードは常に元の完全修飾型
（`apap.domain.model.vo.CapabilityId`等）を指す。したがって、この仕組みが提供しているのは
**ソーステキスト上の分離**（Adapter実装のimport文が`apap.domain.*`ではなく`apap.adapter.spi.*`を
指す、というKonsistで検証可能な事実）であり、**バイナリ／依存関係レベルの分離**ではない。
`AdapterDependencyRuleTest`（Konsist）はこの事実を正しく検証しているが、それが保証しようとしている
性質——「ドメインの変更からAdapter実装を保護する」——は、typealiasの実体が同一クラスである以上、
成立していない。`apap.domain.model.vo.CapabilityId`のフィールド追加・削除やコンストラクタ変更は、
それがビルド上何にも触れていなくても、実行時には`apap.adapter.spi.CapabilityId`を使う既配布済み
Adapter Pluginにそのまま影響する。

既存の`DomainAliases.kt`のKDocは「実行時コストゼロ、実体は同一クラス」と技術的事実は正しく述べて
いるが、これが意味する「ドメイン変更が外部Adapterへ及ぼす実質的な影響」については明記していない。
NFR-MNT-001・15.1（Adapterは独立してバージョニング・配布される前提）を踏まえると、この契約が曖昧な
ままでは、ドメイン変更が外部配布済みAdapter Pluginを予告なく破壊しうる。

なお、本論点は当初 ADR-0015 の見直しとして依頼されたが、`ADR-0015` は
（`adapters/*のtestソースセットはapap-testkitへの依存を許可する`）という別の話題を扱う既存の
Accepted ADRであり、`docs/adr/README.md`の命名規則（番号は連番、欠番・再利用はしない）により
転用できない。そのため本論点は新規に本ADR（ADR-0016）として起票する。

## 決定

1. **Consequencesの明文化**: typealiasはソースレベルの分離のみを提供し、バイナリ／依存関係レベルの
   分離は提供しない。SPI（`apap.adapter.spi`名前空間）経由で露出するドメインVOに対する破壊的変更
   （フィールド削除・型変更・制約強化等）は、**SPI自体の破壊的変更として扱う**。`DomainAliases.kt`の
   KDocにもこの結論を追記する。

2. **SPI公開面（SPI surface）の単一管理箇所**: `apap.adapter.spi.SpiSurface`
   （`modules/apap-adapter-spi/src/main/kotlin/apap/adapter/spi/SpiSurface.kt`）に、
   `DomainAliases.kt`が再エクスポートする全ドメイン型を列挙する。この一覧と実際のtypealias宣言が
   一致することを`SpiSurfaceTest`（Konsist、`modules/apap-adapter-spi/src/test/kotlin/apap/adapter/spi/
   architecture/SpiSurfaceTest.kt`）で検証する。一覧にない型がSPIシグネチャに現れても、一覧にある型が
   消えても、このテストが落ちる。これにより、SPI公開面の変更は必ずdiffに現れ、レビュー対象になる。

3. **SPIバージョニング規約**: `apap-adapter-spi`はsemverで管理し、SPI公開面（本ADRの2.で管理する一覧）
   に含まれるドメインVOの破壊的変更はメジャーバージョン更新を要する。`plugin.yaml`の`spi_version`
   レンジ判定（`SemVerRange`）はこの規約に基づく。規約本文はCLAUDE.md「実装規約」に追記する
   （`docs/design/*.md`は一次情報のため編集しない）。

## 影響（Consequences）

- **制約**: `DomainAliases.kt`にtypealiasを追加・削除・変更する際は、必ず`SpiSurface`の一覧も同時に
  更新しなければならない（さもなくば`SpiSurfaceTest`が失敗する）。これはレビュー時にSPI公開面の変更を
  見逃さないための意図的な摩擦である。
- **見直す条件**: Adapterの隔離実行方式が「プロセス内Plugin」から別クラスローダ／サイドカープロセス
  （16.1「隔離」欄）へ本格的に切り替わり、Adapter実装が実際に別バイトコード境界で動作するようになれば、
  typealiasによる分離の限界という前提自体が変わる。その場合は本ADRを`Superseded`とし、新たなADRで
  再整理する。
- **未決定のまま残る事項**: 型ごとの互換性判定（構造的にどの変更が「非破壊的」とみなせるか）の自動化。
  現状は目視レビュー＋本ADRの規約に依拠する。
- **関連**: CLAUDE.md 不変条件2、CLAUDE.md 実装規約（SPIバージョニング規約）、
  `docs/design/15_Provider追加手順.md` 15.1、`docs/design/16_拡張ポイント.md` 16.1。
