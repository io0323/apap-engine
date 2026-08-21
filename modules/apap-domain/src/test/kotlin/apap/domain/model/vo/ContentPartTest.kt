package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContentPartTest {
    @Test
    fun `Text rejects empty string`() {
        assertThrows(IllegalArgumentException::class.java) { ContentPart.Text("") }
    }

    @Test
    fun `Image requires image mime type and a non blank uri`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentPart.Image("https://example.internal/a.png", "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) { ContentPart.Image(" ", "image/png") }
        ContentPart.Image("https://example.internal/a.png", "image/png")
    }

    @Test
    fun `Audio requires audio mime type and a non blank uri`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentPart.Audio("https://example.internal/a.wav", "video/mp4")
        }
        assertThrows(IllegalArgumentException::class.java) { ContentPart.Audio(" ", "audio/wav") }
        ContentPart.Audio("https://example.internal/a.wav", "audio/wav")
    }

    @Test
    fun `Video requires video mime type and a non blank uri`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContentPart.Video("https://example.internal/a.mp4", "audio/mp4")
        }
        assertThrows(IllegalArgumentException::class.java) { ContentPart.Video(" ", "video/mp4") }
        ContentPart.Video("https://example.internal/a.mp4", "video/mp4")
    }

    @Test
    fun `Json rejects blank payload`() {
        assertThrows(IllegalArgumentException::class.java) { ContentPart.Json("  ") }
        ContentPart.Json("""{"a":1}""")
    }
}
