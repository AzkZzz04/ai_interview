package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatus

/** Service-side request checks. Every failure is a 400 whose message names the field. */
object RequestValidation {
    private val yearMonth = Regex("""\d{4}-(0[1-9]|1[0-2])""")
    private const val MAX_ANSWER_LENGTH = 4_000

    /** Returns [value] trimmed, or throws [code] when its trimmed length is outside [min]..[max]. Null counts as empty. */
    fun text(field: String, value: String?, min: Int, max: Int, code: String = "INVALID_REQUEST"): String {
        val trimmed = value.orEmpty().trim()
        if (trimmed.length !in min..max) throw invalid("$field must be $min to $max characters", code)
        return trimmed
    }

    /** Returns the trimmed answer text (§7.5): blank is `ANSWER_EMPTY`, over 4,000 characters is `ANSWER_TOO_LONG`. */
    fun answer(value: String?): String {
        val trimmed = value.orEmpty().trim()
        if (trimmed.isEmpty()) throw invalid("text must not be blank", "ANSWER_EMPTY")
        if (trimmed.length > MAX_ANSWER_LENGTH) throw invalid("text must be at most $MAX_ANSWER_LENGTH characters", "ANSWER_TOO_LONG")
        return trimmed
    }

    /** Returns [value] when it is null or a `YYYY-MM` month. */
    fun month(field: String, value: String?): String? {
        if (value != null && !yearMonth.matches(value)) throw invalid("$field must be a month in YYYY-MM format")
        return value
    }

    fun invalid(message: String, code: String = "INVALID_REQUEST") =
        ApiRequestException(HttpStatus.BAD_REQUEST, code, message)
}
