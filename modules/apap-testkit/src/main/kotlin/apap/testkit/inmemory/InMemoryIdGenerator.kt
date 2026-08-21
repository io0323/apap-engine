package apap.testkit.inmemory

import apap.domain.port.IdGenerator
import java.util.concurrent.atomic.AtomicLong

/**
 * CLAUDE.md実装規約: IDはPort化して注入する。決定的で、かつ各型付きID VO
 * （`apap.domain.model.vo.Ids`）が要求するULID形式（Crockford Base32、26文字、I/L/O/Uを除く）に
 * 適合する値を生成する。
 */
class InMemoryIdGenerator(
    seed: Long = 1L,
) : IdGenerator {
    private val counter = AtomicLong(seed)

    override fun newId(): String = encode(counter.getAndIncrement())

    private fun encode(value: Long): String {
        val encoded =
            buildString {
                var remaining = value
                repeat(ULID_LENGTH) {
                    val index = (remaining % ALPHABET_SIZE).toInt()
                    append(CROCKFORD_ALPHABET[index])
                    remaining /= ALPHABET_SIZE
                }
            }
        return encoded.reversed()
    }

    companion object {
        private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val ALPHABET_SIZE = 32L
        private const val ULID_LENGTH = 26
    }
}
