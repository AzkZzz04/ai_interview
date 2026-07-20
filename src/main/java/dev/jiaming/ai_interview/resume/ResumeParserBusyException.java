package dev.jiaming.ai_interview.resume;

public class ResumeParserBusyException extends ResumeExtractionException {

	public ResumeParserBusyException() {
		super("Resume parser capacity is temporarily exhausted");
	}
}
