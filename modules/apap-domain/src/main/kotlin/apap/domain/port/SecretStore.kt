package apap.domain.port

import apap.domain.model.vo.CredentialRef

/**
 * ADR-0002: 秘密情報の保管・保存時暗号化・アクセス制御・バージョン保持は外部Storeへ委譲する
 * （APAPコアは特定製品を知らない）。本Portは「参照キー([CredentialRef.secretRef])から実際の値を
 * 読み書きする」という最小限の抽象のみを定義する。Rotationの状態機械（[CredentialRef]の状態遷移）
 * 自体はこのPortの範囲外（`apap.domain.service.provider.CredentialRotationService`が担う、
 * 純粋な状態遷移でありストレージI/Oを伴わない）。
 *
 * 返り値・引数を`CharArray`とするのは、`String`はJVMのString poolに残留しうるため秘密値の扱いに
 * 適さないという一般的なプラクティスに従う（呼び出し側が使用後にゼロ埋め等で破棄する運用を想定）。
 * Adapter実行時に渡す使い捨てラッパー（`apap.adapter.spi.SecretValue`、`close()`で自動ゼロ埋め）は
 * apap-adapter-spi側の関心事であり、apap-domainには持ち込まない（依存ゼロ原則）。ブリッジは
 * Infrastructure層（`apap-infrastructure`）が担う。
 */
interface SecretStore {
    fun resolve(ref: CredentialRef): CharArray

    fun store(
        ref: CredentialRef,
        value: CharArray,
    )
}
