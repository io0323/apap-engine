package apap.observability.audit

/**
 * 02_システム仕様.md 2.18: 本文保存は既定OFF（ハッシュのみ）。[bodyStorageOptIn]をtrueにする
 * （テナントポリシーでのopt-in）場合は必ず[maskingStrategy]を指定すること
 * （[AuditEngine]のinitでガードする。マスキング未設定のまま本文保存が有効化されることを防ぐ）。
 */
data class AuditConfig(
    val bodyStorageOptIn: Boolean = false,
    val maskingStrategy: MaskingStrategy? = null,
)
