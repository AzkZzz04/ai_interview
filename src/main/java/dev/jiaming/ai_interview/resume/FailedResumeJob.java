package dev.jiaming.ai_interview.resume;

import java.util.UUID;

record FailedResumeJob(UUID resumeId, String errorCode, String errorMessage) {
}
