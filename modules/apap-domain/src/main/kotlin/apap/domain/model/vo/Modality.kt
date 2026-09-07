package apap.domain.model.vo

/**
 * 入出力の様式。04_ドメイン設計.md 4.3.2 `ModelCapability.constraints（対応modality…）`が
 * 求めていた「対応modality」を、自由文字列ではなく型として表す。
 *
 * ## なぜ型にするのか（P16）
 *
 * `ModelCapability.constraints` は `Map<String, String>` の自由領域で、**誰も読んでいなかった**。
 * Adapter側の `CapabilityConstraints` にも申告口が無く、結果として
 * 「音声を送れないProviderへ音声リクエストがルーティングされ、実行して初めて失敗する」
 * 状態だった（ADR-0039）。Routingがハードフィルタとして読める形にするには、
 * 突き合わせ可能な列挙である必要がある。
 *
 * [ContentPart]の各実装と1対1に対応する。ContentPartに種別を足したらここも足すこと
 * （`ModalityCoverageTest`が対応漏れを機械検証する）。
 */
enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    JSON,
    ;

    companion object {
        /** [ContentPart]から対応する[Modality]を得る。 */
        fun of(part: ContentPart): Modality =
            when (part) {
                is ContentPart.Text -> TEXT
                is ContentPart.Image -> IMAGE
                is ContentPart.Audio -> AUDIO
                is ContentPart.Video -> VIDEO
                is ContentPart.Json -> JSON
            }

        /** 入力に含まれる全modality。Routingのハードフィルタが要求集合として使う。 */
        fun of(parts: List<ContentPart>): Set<Modality> = parts.map { of(it) }.toSet()
    }
}
