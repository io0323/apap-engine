package apap.domain.model

/**
 * CLAUDE.md 実装規約: 「状態遷移は必ずAggregateのメソッド経由で、不正遷移は専用例外を投げる」。
 * 各Aggregate/VOの不正状態遷移例外はこれを継承する共通基底。
 */
open class IllegalStateTransitionException(
    message: String,
) : IllegalStateException(message)
