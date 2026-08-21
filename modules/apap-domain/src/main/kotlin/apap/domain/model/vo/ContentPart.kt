package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: text | image | audio | video | json のUnion。
 * modalityとCapability制約（対応modality等）の整合検証はCapabilityDefinitionを参照できる
 * 層（Domain Service/Application）が担う。VO単体ではmodality固有の形式のみを検証する。
 */
sealed interface ContentPart {
    data class Text(
        val text: String,
    ) : ContentPart {
        init {
            require(text.isNotEmpty()) { "Text content must not be empty" }
        }
    }

    data class Image(
        val uri: String,
        val mimeType: String,
    ) : ContentPart {
        init {
            require(uri.isNotBlank()) { "Image uri must not be blank" }
            require(mimeType.startsWith("image/")) { "Image mimeType must start with image/: $mimeType" }
        }
    }

    data class Audio(
        val uri: String,
        val mimeType: String,
    ) : ContentPart {
        init {
            require(uri.isNotBlank()) { "Audio uri must not be blank" }
            require(mimeType.startsWith("audio/")) { "Audio mimeType must start with audio/: $mimeType" }
        }
    }

    data class Video(
        val uri: String,
        val mimeType: String,
    ) : ContentPart {
        init {
            require(uri.isNotBlank()) { "Video uri must not be blank" }
            require(mimeType.startsWith("video/")) { "Video mimeType must start with video/: $mimeType" }
        }
    }

    data class Json(
        val json: String,
    ) : ContentPart {
        init {
            require(json.isNotBlank()) { "Json content must not be blank" }
        }
    }
}
