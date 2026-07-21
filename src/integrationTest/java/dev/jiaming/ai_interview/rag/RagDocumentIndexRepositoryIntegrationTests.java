package dev.jiaming.ai_interview.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import dev.jiaming.ai_interview.document.DocumentSourceType;

/**
 * Exercises the real SQL of {@link RagDocumentIndexRepository} against Postgres.
 * The service orchestration is covered separately with a mocked repository in
 * {@code RagIndexingServiceTests}; this class verifies the actual claim fencing,
 * conditional take-over, and cleanup queries that a mock cannot validate.
 */
class RagDocumentIndexRepositoryIntegrationTests {

	private static final Instant BASE = Instant.parse("2026-07-20T12:00:00Z");

	private static final Instant OLD = BASE.minus(Duration.ofDays(10));

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
	)
		.withDatabaseName("ai_interview_rag_repository_test")
		.withUsername("ai_interview")
		.withPassword("ai_interview");

	private static JdbcTemplate jdbcTemplate;

	private static RagDocumentIndexRepository repository;

	@BeforeAll
	static void setUp() {
		POSTGRES.start();
		DataSource dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		repository = new RagDocumentIndexRepository(jdbcTemplate);
	}

	@AfterAll
	static void tearDown() {
		POSTGRES.stop();
	}

	@Test
	void insertClaimCreatesIndexingRowAndConflictIsIgnored() {
		RagDocumentIndexIdentity identity = identity();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertThat(repository.insertClaim(first, identity, BASE)).isTrue();
		assertThat(repository.insertClaim(second, identity, BASE)).isFalse();

		RagDocumentIndex stored = repository.find(identity).orElseThrow();
		assertThat(stored.indexId()).isEqualTo(first);
		assertThat(stored.status()).isEqualTo(RagDocumentIndexStatus.INDEXING);
		assertThat(stored.claimVersion()).isEqualTo(1L);
		assertThat(stored.documentCount()).isZero();
	}

	@Test
	void findReturnsEmptyForUnknownIdentity() {
		assertThat(repository.find(identity())).isEmpty();
	}

	@Test
	void touchReadyOnlyUpdatesMatchingReadyClaim() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity, BASE);
		repository.markReady(indexId, 1L, 3, BASE);

		Instant later = BASE.plus(Duration.ofMinutes(5));
		assertThat(repository.touchReady(indexId, 1L, later)).isTrue();
		assertThat(repository.find(identity).orElseThrow().lastUsedAt()).isEqualTo(later);

		assertThat(repository.touchReady(indexId, 2L, BASE.plus(Duration.ofMinutes(10)))).isFalse();

		UUID indexing = UUID.randomUUID();
		repository.insertClaim(indexing, identity(), BASE);
		assertThat(repository.touchReady(indexing, 1L, later)).isFalse();
	}

	@Test
	void markReadyRequiresIndexingStatusAndMatchingClaim() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity, BASE);

		assertThat(repository.markReady(indexId, 2L, 5, BASE)).isFalse();
		assertThat(repository.markReady(indexId, 1L, 5, BASE)).isTrue();

		RagDocumentIndex ready = repository.find(identity).orElseThrow();
		assertThat(ready.status()).isEqualTo(RagDocumentIndexStatus.READY);
		assertThat(ready.documentCount()).isEqualTo(5);

		assertThat(repository.markReady(indexId, 1L, 9, BASE)).isFalse();
	}

	@Test
	void markFailedRecordsErrorOnlyWhileIndexing() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity, BASE);

		assertThat(repository.markFailed(indexId, 1L, "VECTOR_INDEX_FAILED", BASE)).isTrue();
		assertThat(repository.find(identity).orElseThrow().status()).isEqualTo(RagDocumentIndexStatus.FAILED);
		assertThat(lastError(indexId)).isEqualTo("VECTOR_INDEX_FAILED");

		assertThat(repository.markFailed(indexId, 1L, "AGAIN", BASE)).isFalse();
	}

	@Test
	void takeOverReclaimsStaleIndexingButNotAFreshClaim() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity, BASE);
		RagDocumentIndex fresh = repository.find(identity).orElseThrow();

		// indexing_started_at (BASE) is not before a cutoff in the past -> not eligible.
		assertThat(repository.takeOver(
			fresh, BASE, BASE.minus(Duration.ofMinutes(1)), BASE.minus(Duration.ofMinutes(1))
		)).isFalse();

		Instant now = BASE.plus(Duration.ofMinutes(20));
		assertThat(repository.takeOver(
			fresh, now, now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(5))
		)).isTrue();

		RagDocumentIndex reclaimed = repository.find(identity).orElseThrow();
		assertThat(reclaimed.status()).isEqualTo(RagDocumentIndexStatus.INDEXING);
		assertThat(reclaimed.claimVersion()).isEqualTo(2L);
		assertThat(reclaimed.indexingStartedAt()).isEqualTo(now);

		// A caller still holding the stale claim_version cannot take over again.
		assertThat(repository.takeOver(
			fresh, now, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))
		)).isFalse();
	}

	@Test
	void takeOverReclaimsFailedIndexPastRetryCutoff() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity, BASE);
		repository.markFailed(indexId, 1L, "VECTOR_INDEX_FAILED", BASE);
		RagDocumentIndex failed = repository.find(identity).orElseThrow();

		// updated_at (BASE) not before an earlier cutoff -> not eligible yet.
		assertThat(repository.takeOver(
			failed, BASE, BASE, BASE.minus(Duration.ofMinutes(1))
		)).isFalse();

		Instant now = BASE.plus(Duration.ofMinutes(10));
		assertThat(repository.takeOver(
			failed, now, now, now.minus(Duration.ofMinutes(1))
		)).isTrue();

		RagDocumentIndex reclaimed = repository.find(identity).orElseThrow();
		assertThat(reclaimed.status()).isEqualTo(RagDocumentIndexStatus.INDEXING);
		assertThat(reclaimed.claimVersion()).isEqualTo(2L);
	}

	@Test
	void cleanupCandidatesReturnsStaleTerminalIndexesButNotFreshOnes() {
		Instant cutoff = BASE.minus(Duration.ofDays(7));
		Instant deletingCutoff = BASE.minus(Duration.ofMinutes(10));

		UUID staleReady = seedReady(OLD);
		UUID staleFailed = seedFailed(OLD);
		UUID freshReady = seedReady(BASE);
		UUID freshFailed = seedFailed(BASE);

		List<UUID> candidates = repository.cleanupCandidates(cutoff, deletingCutoff, 100)
			.stream().map(RagDocumentIndex::indexId).toList();

		assertThat(candidates).contains(staleReady, staleFailed);
		assertThat(candidates).doesNotContain(freshReady, freshFailed);
	}

	@Test
	void claimDeletingFencesByClaimVersionAndEligibility() {
		Instant cutoff = BASE.minus(Duration.ofDays(7));
		Instant deletingCutoff = BASE.minus(Duration.ofMinutes(10));
		RagDocumentIndexIdentity identity = identity();
		seedReady(OLD, identity);
		RagDocumentIndex staleReady = repository.find(identity).orElseThrow();

		OptionalLong claimed = repository.claimDeleting(staleReady, cutoff, deletingCutoff, BASE);
		assertThat(claimed).isPresent();
		assertThat(claimed.getAsLong()).isEqualTo(staleReady.claimVersion() + 1);
		assertThat(repository.find(identity).orElseThrow().status()).isEqualTo(RagDocumentIndexStatus.DELETING);

		// The stale caller (old claim_version) can no longer claim it.
		assertThat(repository.claimDeleting(staleReady, cutoff, deletingCutoff, BASE)).isEmpty();

		// A fresh READY row is not eligible for the retention cutoff.
		RagDocumentIndexIdentity freshIdentity = identity();
		seedReady(BASE, freshIdentity);
		RagDocumentIndex freshReady = repository.find(freshIdentity).orElseThrow();
		assertThat(repository.claimDeleting(freshReady, cutoff, deletingCutoff, BASE)).isEmpty();
	}

	@Test
	void deleteClaimedRemovesRowOnlyWhenDeletingAndVersionMatches() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = seedReady(OLD, identity);
		RagDocumentIndex staleReady = repository.find(identity).orElseThrow();
		long deletingVersion = repository.claimDeleting(
			staleReady, BASE.minus(Duration.ofDays(7)), BASE.minus(Duration.ofMinutes(10)), BASE
		).orElseThrow();

		repository.deleteClaimed(indexId, deletingVersion - 1);
		assertThat(repository.find(identity)).isPresent();

		repository.deleteClaimed(indexId, deletingVersion);
		assertThat(repository.find(identity)).isEmpty();
	}

	@Test
	void restoreDeleteFailureReturnsRowToFailed() {
		RagDocumentIndexIdentity identity = identity();
		UUID indexId = seedReady(OLD, identity);
		RagDocumentIndex staleReady = repository.find(identity).orElseThrow();
		long deletingVersion = repository.claimDeleting(
			staleReady, BASE.minus(Duration.ofDays(7)), BASE.minus(Duration.ofMinutes(10)), BASE
		).orElseThrow();

		repository.restoreDeleteFailure(indexId, deletingVersion - 1, BASE);
		assertThat(repository.find(identity).orElseThrow().status()).isEqualTo(RagDocumentIndexStatus.DELETING);

		repository.restoreDeleteFailure(indexId, deletingVersion, BASE);
		assertThat(repository.find(identity).orElseThrow().status()).isEqualTo(RagDocumentIndexStatus.FAILED);
		assertThat(lastError(indexId)).isEqualTo("VECTOR_DELETE_FAILED");
	}

	private UUID seedReady(Instant at) {
		return seedReady(at, identity());
	}

	private UUID seedReady(Instant at, RagDocumentIndexIdentity identity) {
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity, at);
		repository.markReady(indexId, 1L, 1, at);
		return indexId;
	}

	private UUID seedFailed(Instant at) {
		UUID indexId = UUID.randomUUID();
		repository.insertClaim(indexId, identity(), at);
		repository.markFailed(indexId, 1L, "VECTOR_INDEX_FAILED", at);
		return indexId;
	}

	private String lastError(UUID indexId) {
		return jdbcTemplate.queryForObject(
			"SELECT last_error FROM ai_interview_app.rag_document_indexes WHERE index_id = ?",
			String.class,
			indexId
		);
	}

	private RagDocumentIndexIdentity identity() {
		return new RagDocumentIndexIdentity(
			DocumentSourceType.RESUME,
			UUID.randomUUID().toString().replace("-", ""),
			"gemini-embedding-001",
			1_024,
			"section-context-v2"
		);
	}
}
