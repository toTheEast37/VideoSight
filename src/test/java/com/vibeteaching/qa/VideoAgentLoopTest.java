package com.vibeteaching.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels.Kind;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.parsing.ParseModels.UnitStatus;
import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.ParseService.TaskView;
import com.vibeteaching.parsing.ParseService.UnitView;
import com.vibeteaching.qa.VideoAgentLoop.Supplement;
import com.vibeteaching.qa.VideoQaService.Answer;
import com.vibeteaching.qa.VideoQaService.Source;
import com.vibeteaching.qa.VideoQaService.Status;
import com.vibeteaching.qa.persistence.VideoVectorMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoAgentLoopTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final QaProperties CONFIG = new QaProperties(true, "https://example.test/compatible-mode/v1",
            "test-only", "embedding-test", "answer-test", "rerank-test", Duration.ofSeconds(1));

    @Test
    void reviewsEverySubquestionAndReranksOnlyUnresolvedOnesOnce() {
        var model = new ScriptedModel(false);
        var response = agent(model).ask(14, "请分别说明领取、回写和发布条件。");

        assertThat(response.assetId()).isEqualTo(14);
        assertThat(response.parseTaskId()).isEqualTo("saved-parse");
        assertThat(response.subquestions()).extracting(VideoAgentLoop.SubAnswer::status)
                .containsExactly(Status.ANSWERED, Status.ANSWERED, Status.INSUFFICIENT_EVIDENCE);
        assertThat(response.subquestions()).extracting(VideoAgentLoop.SubAnswer::supplement)
                .containsExactly(Supplement.NOT_NEEDED, Supplement.COMPLETED, Supplement.COMPLETED);
        assertThat(response.subquestions().get(0).items().get(0).text()).isEqualTo("审查修正后的内容");
        Source recovered = response.subquestions().get(1).items().get(0).citations().get(0);
        assertThat(recovered).isEqualTo(new Source("ASR-3", Kind.ASR, 180_000, 240_000, "执行令牌"));
        assertThat(response.subquestions().get(2).items()).isEmpty();
        assertThat(model.reranked).containsExactly("限定甲主题", "限定乙主题");
        assertThat(model.reviews).containsExactly("领取", "回写", "发布", "回写", "发布");
    }

    @Test
    void keepsReviewedEvidenceAndReportsUnavailableSupplement() {
        var model = new ScriptedModel(true);
        var response = agent(model).ask(14, "请分别说明领取、回写和发布条件。");
        assertThat(response.subquestions()).extracting(VideoAgentLoop.SubAnswer::supplement)
                .containsExactly(Supplement.NOT_NEEDED, Supplement.FAILED, Supplement.FAILED);
        assertThat(response.subquestions().get(1).status()).isEqualTo(Status.PARTIAL);
        assertThat(response.subquestions().get(1).items()).hasSize(1);
        assertThat(model.reranked).containsExactly("限定甲主题", "限定乙主题");
        assertThat(model.reviews).containsExactly("领取", "回写", "发布");
    }

    @Test
    void rejectsCriticCitationThatDoesNotComeFromSelectedEvidence() {
        var model = new ScriptedModel(false) {
            @Override public JsonNode review(String question, Answer draft, List<Source> sources) {
                return supported("错误结论", "ASR-7", "不存在的引文", "ANSWERED", "");
            }
        };
        assertThatThrownBy(() -> agent(model).ask(14, "查找证据。"))
                .isInstanceOfSatisfying(QaException.class, error ->
                        assertThat(error.code()).isEqualTo("QA_INVALID_MODEL_RESPONSE"));
        assertThat(model.reranked).isEmpty();
    }

    private static VideoAgentLoop agent(QaModelClient model) {
        List<UnitView> units = List.of(unit(0, "只有一位消费者能领取任务"), unit(1, "保存临时对象"),
                unit(2, "任务显示等待"), unit(3, "核验执行令牌后保存结果"));
        ParseService parsing = new ParseService(null, null, null, null) {
            @Override public TaskView byAsset(long assetId) {
                assertThat(assetId).isEqualTo(14);
                return new TaskView("saved-parse", assetId, "profile", TaskStatus.SUCCEEDED,
                        240_000L, Instant.EPOCH, Instant.EPOCH, false, units);
            }
        };
        VideoVectorMapper storage = new VideoVectorMapper() {
            @Override public List<VectorRow> find(long assetId, List<String> keys) { return List.of(); }
            @Override public void save(long assetId, String key, String embeddingJson) { }
        };
        return new VideoAgentLoop(new VideoQaService(parsing, storage, model, CONFIG, JSON), model);
    }

    private static UnitView unit(int index, String text) {
        long start = index * 60_000L;
        return new UnitView(Kind.ASR, index, UnitStatus.SUCCEEDED, 1, start, start + 60_000, text, null, null);
    }

    private static JsonNode supported(String text, String id, String quote, String status, String next) {
        return JSON.valueToTree(Map.of("status", status, "nextQuery", next, "items",
                List.of(Map.of("text", text, "citations", List.of(Map.of("id", id, "quote", quote))))));
    }

    private static JsonNode insufficient(String query) {
        return JSON.valueToTree(Map.of("status", "INSUFFICIENT_EVIDENCE", "items", List.of(), "nextQuery", query));
    }

    private static class ScriptedModel extends QaModelClient {
        final List<String> reranked = new ArrayList<>();
        final List<String> reviews = new ArrayList<>();
        private final boolean failRerank;

        ScriptedModel(boolean failRerank) {
            super(CONFIG, JSON);
            this.failRerank = failRerank;
        }

        @Override public List<float[]> embed(List<String> inputs) {
            return inputs.stream().map(input -> {
                float[] vector = new float[DIMENSIONS];
                vector[0] = 1;
                return vector;
            }).toList();
        }

        @Override public JsonNode plan(String question) {
            return JSON.valueToTree(Map.of("questions", List.of("领取", "回写", "发布")));
        }

        @Override public JsonNode answer(String question, List<Source> sources) {
            return switch (question) {
                case "领取" -> supported("需要审查的草稿", "ASR-0", "一位消费者", "ANSWERED", "");
                case "回写" -> sources.stream().anyMatch(source -> source.id().equals("ASR-3"))
                        ? supported("核验执行令牌", "ASR-3", "执行令牌", "ANSWERED", "")
                        : insufficient("");
                default -> insufficient("");
            };
        }

        @Override public JsonNode review(String question, Answer draft, List<Source> sources) {
            reviews.add(question);
            return switch (question) {
                case "领取" -> supported("审查修正后的内容", "ASR-0", "一位消费者", "ANSWERED", "");
                case "回写" -> draft.status() == Status.ANSWERED
                        ? supported("核验执行令牌", "ASR-3", "执行令牌", "ANSWERED", "")
                        : failRerank ? supported("先领取任务", "ASR-0", "领取任务", "PARTIAL", "限定甲主题")
                          : insufficient("限定甲主题");
                default -> insufficient("限定乙主题");
            };
        }

        @Override public List<Integer> rerank(String query, List<String> documents) {
            reranked.add(query);
            assertThat(documents).hasSize(4);
            if (failRerank) throw new QaException(HttpStatus.BAD_GATEWAY, "QA_MODEL_UNAVAILABLE", "重排不可用");
            return List.of(3, 0, 1, 2);
        }
    }
}
