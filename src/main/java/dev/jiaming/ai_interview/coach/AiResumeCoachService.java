package dev.jiaming.ai_interview.coach;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import dev.jiaming.ai_interview.gemini.GeminiClient;

@Service
public class AiResumeCoachService {

	private final GeminiClient geminiClient;

	private final CoachResumeResolver resumeResolver;

	private final CoachRagContextService ragContextService;

	private final CoachPromptBuilder promptBuilder;

	private final CoachResponseMapper responseMapper;

	public AiResumeCoachService(
		GeminiClient geminiClient,
		CoachResumeResolver resumeResolver,
		CoachRagContextService ragContextService,
		CoachPromptBuilder promptBuilder,
		CoachResponseMapper responseMapper
	) {
		this.geminiClient = geminiClient;
		this.resumeResolver = resumeResolver;
		this.ragContextService = ragContextService;
		this.promptBuilder = promptBuilder;
		this.responseMapper = responseMapper;
	}

	public AssessmentResponse assess(AiAnalysisRequest request) {
		String resumeText = resumeResolver.resolve(request.resumeText());
		return runAssessment(request, resumeText);
	}

	public InterviewQuestionsResponse generateQuestions(AiAnalysisRequest request) {
		String resumeText = resumeResolver.resolve(request.resumeText());
		return runQuestions(request, resumeText);
	}

	public AnswerFeedbackResponse scoreAnswer(AnswerFeedbackRequest request) {
		if (blank(request.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}

		String resumeText = resumeResolver.resolve(request.resumeText());
		return runFeedback(request, resumeText);
	}

	public AssessmentResponse runAssessment(AiAnalysisRequest request, String resumeText) {
		CoachRagContext context = ragContextService.assessmentContext(request, resumeText);
		String prompt = promptBuilder.buildAssessmentPrompt(request, context);
		AssessmentResponse response = responseMapper.parse(geminiClient.generateJson(prompt), AssessmentResponse.class);
		return responseMapper.normalizeAssessment(response, context.sourceContextIds());
	}

	public InterviewQuestionsResponse runQuestions(AiAnalysisRequest request, String resumeText) {
		CoachRagContext context = ragContextService.questionContext(request, resumeText);
		String prompt = promptBuilder.buildQuestionPrompt(request, context);
		InterviewQuestionsResponse response = responseMapper.parse(
			geminiClient.generateJson(prompt),
			InterviewQuestionsResponse.class
		);
		return responseMapper.normalizeQuestions(response, context.sourceContextIds());
	}

	public AnswerFeedbackResponse runFeedback(AnswerFeedbackRequest request, String resumeText) {
		if (blank(request.answerText())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required");
		}
		CoachRagContext context = ragContextService.feedbackContext(request, resumeText);
		String prompt = promptBuilder.buildFeedbackPrompt(request, context);
		AnswerFeedbackResponse response = responseMapper.parse(geminiClient.generateJson(prompt), AnswerFeedbackResponse.class);
		return responseMapper.normalizeFeedback(response, context.sourceContextIds());
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

}
