package com.vibeteaching.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.ParseService.TaskView;
import com.vibeteaching.parsing.ParseService.UnitView;
import com.vibeteaching.persistence.RepositoryTestSupport;
import com.vibeteaching.qa.persistence.VideoVectorMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoQaServiceTest {
    private static final String QUESTION = "画面哪里出现 lease_token？";
    private static final String OCR = "UPDATE ... WHERE lease_token = ?";
    private static final QaProperties CONFIG = new QaProperties(true, "https://example.test/compatible-mode/v1",
            "test-only", "embedding-test", "answer-test", "rerank-test", Duration.ofSeconds(1));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void usesCleanOcrForEmbeddingAndRerankButKeepsRawEvidenceAndTime() {
        String raw = "C++　GET\r\n\r\n\u200BSELECT   assetId FROM video_asset;\r\n爹 查 路 径";
        String clean = "C++ GET\nSELECT assetId FROM video_asset;\n爹查路径";
        var task = task(1, TaskStatus.SUCCEEDED, List.of(
                new UnitView(Kind.OCR, 0, UnitStatus.SUCCEEDED, 1, 7500, 7500, raw, null, null),
                new UnitView(Kind.OCR, 1, UnitStatus.SUCCEEDED, 1, 8500, 8500, clean, null, null)));
        var model = new LocalModel();
        var service = service(task, new VideoVectorMapper() {
            @Override public List<VectorRow> find(long assetId, List<String> keys) { return List.of(); }
            @Override public void save(long assetId, String key, String embeddingJson) { }
        }, model);
        var observed = service.retrieve(service.prepare(1), QUESTION, true);
        assertThat(model.embedded).containsExactly(List.of(clean), List.of(QUESTION));
        assertThat(model.reranked).containsExactly(clean);
        assertThat(observed).containsExactly(new VideoQaService.Source("OCR-0", Kind.OCR, 7500, 7500, raw));
    }

    @Test
    void savesOnlyThisAssetsVectorsAndReusesThemAfterReopeningMapper() throws Exception {
        String database = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var source = new DriverManagerDataSource(database, "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        addAsset(jdbc, 1);
        addAsset(jdbc, 2);
        TaskView task = task(1, TaskStatus.SUCCEEDED, List.of(
                new UnitView(Kind.OCR, 0, UnitStatus.SUCCEEDED, 1, 7500, 7500, OCR, null, null),
                new UnitView(Kind.OCR, 1, UnitStatus.SUCCEEDED, 1, 8500, 8500, OCR, null, null),
                new UnitView(Kind.ASR, 0, UnitStatus.FAILED, 1, 0, 60000, "其他文字", null, null)));
        var model = new LocalModel();
        VideoQaService service = service(task, mapper(source), model);
        var answer = service.ask(1, QUESTION);
        assertThat(answer.assetId()).isEqualTo(1);
        assertThat(answer.items().get(0).citations()).containsExactly(
                new VideoQaService.Source("OCR-0", Kind.OCR, 7500, 7500, "lease_token = ?"));
        assertThat(model.embedded).containsExactly(List.of(OCR), List.of(QUESTION));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_search_vector WHERE asset_id = 1", Integer.class)).isOne();

        var reopenedSource = new DriverManagerDataSource(database, "sa", "");
        var reopenedModel = new LocalModel();
        service(task, mapper(reopenedSource), reopenedModel).ask(1, QUESTION);
        assertThat(reopenedModel.embedded).containsExactly(List.of(QUESTION));

        var otherModel = new LocalModel();
        service(task(2, task.status(), task.units()), mapper(reopenedSource), otherModel).ask(2, QUESTION);
        assertThat(otherModel.embedded).containsExactly(List.of(OCR), List.of(QUESTION));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_search_vector", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsFabricatedQuotesAndKeepsInsufficientEvidenceExplicit() throws Exception {
        TaskView task = task(1, TaskStatus.PARTIAL, List.of());
        var source = new VideoQaService.Source("OCR-0", Kind.OCR, 7500, 7500, OCR);
        JsonNode invented = json.readTree("""
                {"status":"ANSWERED","items":[{"text":"结果","citations":[{"id":"OCR-0","quote":"不存在的引文"}]}]}
                """);
        assertThatThrownBy(() -> VideoQaService.validateAnswer(task, List.of(source), invented))
                .isInstanceOfSatisfying(QaException.class, error ->
                        assertThat(error.code()).isEqualTo("QA_INVALID_MODEL_RESPONSE"));
        var insufficient = service(task, null, null).ask(1, QUESTION);
        assertThat(insufficient.status()).isEqualTo(VideoQaService.Status.INSUFFICIENT_EVIDENCE);
        assertThat(insufficient.parsePartial()).isTrue();
        assertThat(insufficient.items()).isEmpty();
    }

    private VideoQaService service(TaskView task, VideoVectorMapper vectors, LocalModel model) {
        ParseService parsing = new ParseService(null, null, null, null) {
            @Override public TaskView byAsset(long assetId) {
                assertThat(assetId).isEqualTo(task.assetId());
                return task;
            }
        };
        return new VideoQaService(parsing, vectors, model, CONFIG, json);
    }

    private static VideoVectorMapper mapper(DriverManagerDataSource source) {
        var session = RepositoryTestSupport.mapperSession(source);
        session.getConfiguration().addMapper(VideoVectorMapper.class);
        return session.getMapper(VideoVectorMapper.class);
    }

    private static TaskView task(long id, TaskStatus status, List<UnitView> units) {
        return new TaskView(UUID.randomUUID().toString(), id, "profile", status, 9000L,
                Instant.EPOCH, Instant.EPOCH, false, units);
    }

    private static void addAsset(JdbcTemplate jdbc, long id) {
        String upload = UUID.randomUUID().toString();
        String sha = "a".repeat(64);
        jdbc.update("""
                INSERT INTO upload_session(upload_id, request_key, original_name, file_size, chunk_size, total_chunks,
                  file_fingerprint, content_type, status, expires_at, created_at, updated_at)
                VALUES (?, ?, 'sample.mp4', 1, 1, 1, ?, 'video/mp4', 'COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, upload, UUID.randomUUID().toString(), sha);
        jdbc.update("""
                INSERT INTO video_asset(asset_id, upload_id, original_name, file_size, sha256, bucket, object_key, content_type, created_at)
                VALUES (?, ?, 'sample.mp4', 1, ?, 'test', 'sample.mp4', 'video/mp4', CURRENT_TIMESTAMP)
                """, id, upload, sha);
    }

    private final class LocalModel extends QaModelClient {
        final List<List<String>> embedded = new ArrayList<>();
        final List<String> reranked = new ArrayList<>();

        LocalModel() { super(CONFIG, VideoQaServiceTest.this.json); }

        @Override public List<Integer> rerank(String query, List<String> documents) {
            reranked.addAll(documents);
            return List.of(0);
        }

        @Override public List<float[]> embed(List<String> inputs) {
            embedded.add(List.copyOf(inputs));
            return inputs.stream().map(text -> {
                float[] vector = new float[DIMENSIONS];
                vector[0] = 1;
                return vector;
            }).toList();
        }

        @Override public JsonNode answer(String question, List<VideoQaService.Source> sources) {
            assertThat(sources).hasSize(1);
            return json.valueToTree(Map.of("status", "ANSWERED", "items", List.of(Map.of(
                    "text", "画面出现了 lease_token 条件。", "citations", List.of(
                            Map.of("id", sources.get(0).id(), "quote", "lease_token = ?"))))));
        }
    }
}
