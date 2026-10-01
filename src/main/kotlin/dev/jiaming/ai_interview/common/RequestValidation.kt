package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatus

/** Service-side request checks. Every failure is a 400 whose message names the field. */
object RequestValidation {
    private val yearMonth = Regex("""\d{4}-(0[1-9]|1[0-2])""")

    /** Returns [value] trimmed, or throws [code] when its trimmed length is outside [min]..[max]. Null counts as empty. */
    fun text(field: String, value: String?, min: Int, max: Int, code: String = "INVALID_REQUEST"): String {
        val trimmed = value.orEmpty().trim()
        if (trimmed.length !in min..max) throw invalid("$field must be $min to $max characters", code)
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
