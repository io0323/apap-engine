package apap.domain.model.vo

// Crockford Base32、I/L/O/Uを含まない25文字の固定プレフィックス。
// 末尾1文字を変えるだけで、テストごとに異なる有効なULID文字列（26文字）を安価に得られる。
private const val ULID_BASE_25 = "01ARZ3NDEKTSV4RRFFQ69G5FA"

fun testUlid(suffix: Char = 'V'): String = ULID_BASE_25 + suffix
