package dev.jiaming.ai_interview.resume

open class ResumeExtractionException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
