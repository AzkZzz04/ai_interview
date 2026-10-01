package dev.jiaming.ai_interview.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class RequestValidationTests {

    @Test
    fun rejectsEmptyAndTooLongNamesNamingTheField() {
        for (name in listOf("   ", "a".repeat(81))) {
            val error = catchThrowableOfType(ApiRequestException::class.java) { RequestValidation.text("name", name, 1, 80) }
            assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(error.code()).isEqualTo("INVALID_REQUEST")
            assertThat(error.message).startsWith("name ")
        }
    }

    @Test
    fun acceptsOneAndEightyCharacterNamesAfterTrimming() {
        assertThat(RequestValidation.text("name", "  a  ", 1, 80)).isEqualTo("a")
        assertThat(RequestValidation.text("name", " ${"a".repeat(80)} ", 1, 80)).isEqualTo("a".repeat(80))
    }

    @Test
    fun throwsTheGivenSpecificCode() {
        val error = catchThrowableOfType(ApiRequestException::class.java) {
            RequestValidation.text("answer", " ", 1, 4_000, "ANSWER_EMPTY")
        }
        assertThat(error.code()).isEqualTo("ANSWER_EMPTY")
    }

    @Test
    fun checksYearMonth() {
        for (month in listOf("2023-13", "23-01")) {
            val error = catchThrowableOfType(ApiRequestException::class.java) { RequestValidation.month("startDate", month) }
            assertThat(error.code()).isEqualTo("INVALID_REQUEST")
            assertThat(error.message).startsWith("startDate ")
        }
        assertThat(RequestValidation.month("startDate", "2023-01")).isEqualTo("2023-01")
        assertThat(RequestValidation.month("endDate", null)).isNull()
    }
}
