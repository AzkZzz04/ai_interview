package dev.jiaming.ai_interview.jobs;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class JobHandlerRegistry {

	private final Map<JobType, JobHandler<?>> handlers;

	public JobHandlerRegistry(List<JobHandler<?>> registeredHandlers) {
		EnumMap<JobType, JobHandler<?>> indexed = new EnumMap<>(JobType.class);
		for (JobHandler<?> handler : registeredHandlers) {
			JobHandler<?> duplicate = indexed.putIfAbsent(handler.type(), handler);
			if (duplicate != null) {
				throw new IllegalStateException("Multiple job handlers registered for " + handler.type());
			}
		}

		EnumSet<JobType> missing = EnumSet.allOf(JobType.class);
		missing.removeAll(indexed.keySet());
		if (!missing.isEmpty()) {
			throw new IllegalStateException("Missing job handlers for " + missing);
		}
		this.handlers = Map.copyOf(indexed);
	}

	public JobHandler<?> require(JobType type) {
		JobHandler<?> handler = handlers.get(type);
		if (handler == null) {
			throw new IllegalArgumentException("No job handler registered for " + type);
		}
		return handler;
	}
}
