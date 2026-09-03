# ADR-0033: 監査ログ検索はテナントスコープを必須にする

- **ステータス**: Accepted
- **関連要件**: FR-SEC-003（Access Control: テナント分離）, FR-SEC-006（監査要件）, NFR-SEC-003
- **関連する設計書**: 13_API設計.md 13.1（`GET /admin/v1/audit`）, 12_ER図.md（`audit_record.tenant_id`）, 04_ドメイン設計.md 4.6
- **検出**: P11 総合検証（P11-D3 / 実装側の症状は P11-F9）

## コンテキスト

`AuditRepository` のPort契約は次の形をしている。

```kotlin
interface AuditRepository {
    fun append(record: AuditRecord)
    fun search(criteria: AuditSearchCriteria): List<AuditRecord>
}

data class AuditSearchCriteria(
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val tenantId: TenantId? = null,   // ← nullable、既定null
    ...
)
```

`tenantId` が**nullableかつ既定null**であるため、`search(AuditSearchCriteria())` は
**全テナントの監査ログを返す**。テナント境界はPort契約上どこにも強制されていない。

他のテナント境界付きPortはすべて `tenantId` を非nullの必須引数として持ち、
`TenantScopedRepositoryTest` がそれを機械検証している。`AuditRepository` だけがこの検証の
対象外だった——`AuditRepository.kt` が `TenantId` に言及していないため、
「TenantIdに言及するPortを検証対象にする」という網羅ルールからも漏れていた
（P11でこの網羅ルール自体を追加し、`AuditRepository` が漏れていることを検出した）。

設計書側にもこの点の定義が無い。13.1は `GET /admin/v1/audit` を「監査検索」とだけ記し、
テナント境界の扱いを述べていない。12_ER図.mdは `audit_record.tenant_id` を定義しているが、
検索時に必須かどうかは書かれていない。

現時点で実際に悪用可能な経路は存在しない（`AuditEngine` が本番配線に無く監査ログが
そもそも記録されない＝P11-F1、かつ `GET /admin/v1/audit` はNOT_IMPLEMENTED）。
しかし**Port契約がテナント横断の読み出しを許している**限り、AuditEngineを配線し
Admin APIを実装した時点で、境界のないAPIが自然にできあがる。実装前に契約を直す。

CLAUDE.md 不変条件8の判断基準に照らすと、この曖昧さの解釈次第で FR-SEC-003
（テナント分離）が満たせなくなるため、ADRを起票する。

## 決定

1. `AuditSearchCriteria.tenantId` を**非nullの必須フィールド**にする。
   デフォルト値を与えない（「指定し忘れたら全件」という失敗の形を型で塞ぐ）。
2. プラットフォーム運用者によるテナント横断検索が必要な場合は、`search` とは別に
   `searchAcrossTenants(criteria)` を明示的な別メソッドとして定義する。
   呼び出し側のコードに「テナントを跨いでいる」ことが字面で現れるようにし、
   Admin APIのスコープ（権限）判定でこのメソッドの利用を限定できるようにする。
   本ADR時点ではこのメソッドを**追加しない**（必要になるまで作らない）。
3. `TenantScopedRepositoryTest` の検証対象に `AuditRepository.search` を追加する。
   併せて、検証対象リストの網羅性（TenantIdに言及するPortは検証対象か理由付き除外のいずれか）を
   同テストで強制する。この網羅ルールはP11で実装済み。

## 影響（Consequences）

- `AuditEngine`（`append`のみ使用）への影響は無い。影響を受けるのは検索側のみで、
  現在の呼び出し元は `AuditEngineTest` / `InMemoryAuditRepositoryTest` /
  `JdbcAuditRepositoryTest` / `CapabilitySmokeTest` のテストコードに限られる。
- `GET /admin/v1/audit` を実装する際、テナントIDは認証済みトークンのクレームから取り、
  クエリパラメータで上書きできないようにする必要がある（上書きを許すと1の効果が消える）。
  この点はエンドポイント実装時のレビュー対象として `docs/verification-report.md` に記載する。
- 2を将来追加する場合、プラットフォーム管理者ロールの定義（現在はADMINスコープ単一）を
  細分化する必要がある。FR-SEC-003の「オペレーション別権限」と同時に設計する。
