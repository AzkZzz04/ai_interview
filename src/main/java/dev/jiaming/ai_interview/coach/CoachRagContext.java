package dev.jiaming.ai_interview.coach;

import java.util.List;

record CoachRagContext(
	String contextKey,
	String context,
	List<String> sourceContextIds,
	boolean vectorBacked
) {
}
