package dev.jiaming.ai_interview.coach;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiErrorCode;
import dev.jiaming.ai_interview.gemini.GeminiException;

@Service
public class AiResumeCoachService {

	private final StructuredGenerationClient generationClient;

	private final CoachRagContextService ragContextService;

	private final CoachPromptBuilder promptBuilder;

	private final CoachResponseMapper responseMapper;

	private final MeterRegistry meterRegistry;

	public AiResumeCoachService(
		StructuredGenerationClient generationClient,
		CoachRagContextService ragContextService,
		CoachPromptBuilder promptBuilder,
		CoachResponseMapper responseMapper,
		MeterRegistry meterRegistry
	) {
		this.generationClient = generationClient;
		this.ragContextService = ragContextService;
		this.promptBuilder = promptBuilder;
		this.responseMapper = responseMapper;
		this.meterRegistry = meterRegistry;
	}

	public AssessmentResponse assess(CoachAnalysisInput input) {
		CoachRagContext context = ragContextService.assessmentContext(input);
		String prompt = promptBuilder.buildAssessmentPrompt(input, context);
		AssessmentResponse response = generateStructured(prompt, AssessmentResponse.class);
		return responseMapper.normalizeAssessment(response, context.sourceContextIds());
	}

	public InterviewQuestionsResponse generateQuestions(CoachAnalysisInput input) {
		CoachRagContext context = ragContextService.questionContext(input);
		String prompt = promptBuilder.buildQuestionPrompt(input, context);
		InterviewQuestionsResponse response = generateStructured(prompt, InterviewQuestionsResponse.class);
		return responseMapper.normalizeQuestions(response, context.sourceContextIds());
	}

	public AnswerFeedbackResponse scoreAnswer(CoachFeedbackInput input) {
		if (blank(input.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}
		CoachRagContext context = ragContextService.feedbackContext(input);
		String prompt = promptBuilder.buildFeedbackPrompt(input, context);
		AnswerFeedbackResponse response = generateStructured(prompt, AnswerFeedbackResponse.class);
		return responseMapper.normalizeFeedback(response, context.sourceContextIds());
	}

	private <T> T generateStructured(String prompt, Class<T> responseType) {
		String firstOutput = generationClient.generateJson(prompt);
		try {
			return responseMapper.parse(firstOutput, responseType);
		}
		catch (GeminiException firstFailure) {
			if (!GeminiErrorCode.INVALID_RESPONSE.equals(firstFailure.code())) {
				throw firstFailure;
			}
			meterRegistry.counter("ai.gemini.schema_repair", "outcome", "attempted").increment();
			String parseError = firstFailure.getCause() == null
				? firstFailure.getMessage()
				: firstFailure.getCause().getMessage();
			String repairedOutput = generationClient.generateJson(
				promptBuilder.buildRepairPrompt(prompt, firstOutput, parseError)
			);
			try {
				T repaired = responseMapper.parse(repairedOutput, responseType);
				meterRegistry.counter("ai.gemini.schema_repair", "outcome", "succeeded").increment();
				return repaired;
			}
			catch (GeminiException secondFailure) {
				meterRegistry.counter("ai.gemini.schema_repair", "outcome", "failed").increment();
				throw new GeminiException(
					GeminiErrorCode.INVALID_RESPONSE,
					"Gemini response remained invalid after one schema repair",
					secondFailure,
					false
				);
			}
		}
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
