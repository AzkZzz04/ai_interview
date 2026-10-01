package dev.jiaming.ai_interview.history

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HistoryController(private val historyService: HistoryService) {
    @GetMapping("/api/history")
    fun history(): HistoryResponse = historyService.history()
}
