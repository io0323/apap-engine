package apap.adapter.spi

import java.time.Instant

/**
 * 03_基本設計.md 3.3.2: `authenticate(): AuthContext`。Adapter内部で解決した認証情報
 * （署名済みヘッダ等、Provider固有の中身はAdapter実装に閉じる）を、後続の`execute`呼出へ
 * 明示的に運ぶための不透明なコンテナ。Credentialそのもの（秘密値）は保持しない
 * （01_CLAUDE.md 不変条件4）。
 */
data class AuthContext(
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Instant? = null,
)

/** `validateCredential(ref): ValidationResult`。検証結果と、失敗時の人間可読な詳細。 */
data class ValidationResult(
    val valid: Boolean,
    val detail: String? = null,
)
