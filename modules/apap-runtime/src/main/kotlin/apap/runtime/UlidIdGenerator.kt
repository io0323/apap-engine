package apap.runtime

import apap.domain.port.IdGenerator
import java.security.SecureRandom
import java.time.Clock as JavaClock

/**
 * [ApapEngineBuilder]の既定[IdGenerator]実装（CLAUDE.md実装規約: 直接の`UUID.randomUUID()`等の
 * 呼び出しはこのPortの実装クラス自身にのみ許可される、`ClockAndIdGeneratorDirectCallTest`の
 * allowlist参照）。04_ドメイン設計.md 4.4が要求するULID形式（Crockford Base32、26文字、
 * I/L/O/Uを除く、`apap.domain.model.vo.Ulid.isValid`が検証する形式）で生成する:
 * 先頭10文字=48bitミリ秒タイムスタンプ、残り16文字=80bitの暗号学的乱数。
 *
 * 業務ロジックが依存する時刻（`apap.domain.port.Clock`）とは無関係の目的（IDのソート可能性のための
 * タイムスタンプ埋め込み）のため、[java.time.Clock]を直接使う（[apap.domain.port.Clock]は
 * 注入せず、システム時刻をそのまま使う——テストの決定性が要求されるのはドメインロジックが読む
 * 時刻であり、IDのユニーク性のための時刻成分ではないため。要件充足に影響しない実装判断のため
 * ADR化せずここに根拠を記す）。
 */
class UlidIdGenerator : IdGenerator {
    private val random = SecureRandom()
    private val clock = JavaClock.systemUTC()

    override fun newId(): String {
        val timestampMs = clock.millis()
        val randomBytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        val bits = BitBuffer(TOTAL_BITS)
        bits.putBits(timestampMs, TIMESTAMP_BITS)
        randomBytes.forEach { byte -> bits.putBits(byte.toLong() and BYTE_MASK, Byte.SIZE_BITS) }
        return bits.toCrockfordBase32()
    }

    private class BitBuffer(
        totalBits: Int,
    ) {
        private val bits = BooleanArray(totalBits)
        private var cursor = 0

        fun putBits(
            value: Long,
            count: Int,
        ) {
            for (i in count - 1 downTo 0) {
                bits[cursor++] = ((value shr i) and 1L) == 1L
            }
        }

        fun toCrockfordBase32(): String =
            buildString {
                var i = 0
                while (i < bits.size) {
                    var chunk = 0
                    repeat(BITS_PER_CHAR) { offset ->
                        chunk = (chunk shl 1) or (if (bits[i + offset]) 1 else 0)
                    }
                    append(CROCKFORD_ALPHABET[chunk])
                    i += BITS_PER_CHAR
                }
            }
    }

    private companion object {
        const val TIMESTAMP_BITS = 48
        const val RANDOM_BYTES = 10
        const val BITS_PER_CHAR = 5
        const val ULID_LENGTH = 26
        const val TOTAL_BITS = ULID_LENGTH * BITS_PER_CHAR
        const val BYTE_MASK = 0xFFL
        const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
