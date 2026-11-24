package com.vibeteaching.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import com.vibeteaching.qa.VideoQaService.Answer;
import com.vibeteaching.qa.VideoQaService.Item;
import com.vibeteaching.qa.VideoQaService.SearchContext;
import com.vibeteaching.qa.VideoQaService.Source;
import com.vibeteaching.qa.VideoQaService.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = {"app.upload.enabled", "app.qa.enabled"}, havingValue = "true")
public class VideoAgentLoop {
    private static final int MAX_SUBQUESTIONS = 3;
    private final VideoQaService qa;
    private final QaModelClient model;

    public VideoAgentLoop(VideoQaService qa, QaModelClient model) {
        this.qa = qa;
        this.model = model;
    }

    public AgentAnswer ask(long assetId, String input) {
        String question = VideoQaService.validateQuestion(assetId, input);
        SearchContext context = qa.prepare(assetId);
        if (context.empty()) {
            return result(context, List.of(new SubAnswer(question, Status.INSUFFICIENT_EVIDENCE, Supplement.NOT_NEEDED, List.of())));
        }

        // 完成所有首轮审查后，只为仍有缺口的子问题补查一次。
        List<Review> firstRound = new ArrayList<>();
        for (String subquestion : plan(model.plan(question))) {
            List<Source> sources = qa.retrieve(context, subquestion, false);
            firstRound.add(executeAndReview(context, subquestion, sources));
        }
        List<SubAnswer> answers = new ArrayList<>();
        for (Review first : firstRound) {
            answers.add(first.answer().status() == Status.ANSWERED
                    ? subAnswer(first, Supplement.NOT_NEEDED) : supplement(context, first));
        }
        return result(context, List.copyOf(answers));
    }

    private Review executeAndReview(SearchContext context, String question, List<Source> sources) {
        Answer draft = VideoQaService.validateAnswer(context.task(), sources, model.answer(question, sources));
        JsonNode verdict = model.review(question, draft, sources);
        Answer reviewed = VideoQaService.validateAnswer(context.task(), sources, verdict);
        String nextQuery = verdict.path("nextQuery").isTextual() ? verdict.path("nextQuery").textValue().strip() : "";
        if (nextQuery.isBlank() || nextQuery.length() > VideoQaService.MAX_QUESTION_LENGTH) nextQuery = question;
        return new Review(question, sources, reviewed, nextQuery);
    }

    private SubAnswer supplement(SearchContext context, Review first) {
        try {
            List<Source> additional = qa.retrieve(context, first.nextQuery(), true);
            var combined = new LinkedHashMap<String, Source>();
            additional.forEach(source -> combined.put(source.id(), source));
            first.sources().forEach(source -> combined.putIfAbsent(source.id(), source));
            Review verified = executeAndReview(context, first.question(), List.copyOf(combined.values()));
            return subAnswer(verified, Supplement.COMPLETED);
        } catch (QaException failure) {
            // 补证调用失败时，保留首轮已核对的结论并向页面报告本次失败。
            return subAnswer(first, Supplement.FAILED);
        }
    }

    private static List<String> plan(JsonNode reply) {
        JsonNode questions = reply == null ? null : reply.path("questions");
        if (questions == null || !questions.isArray() || questions.isEmpty() || questions.size() > MAX_SUBQUESTIONS) {
            throw QaException.invalidModelOutput();
        }
        var distinct = new LinkedHashSet<String>();
        for (JsonNode question : questions) {
            if (!question.isTextual() || question.textValue().isBlank()
                    || question.textValue().length() > VideoQaService.MAX_QUESTION_LENGTH
                    || !distinct.add(question.textValue().strip())) throw QaException.invalidModelOutput();
        }
        return List.copyOf(distinct);
    }

    private static SubAnswer subAnswer(Review review, Supplement supplement) {
        return new SubAnswer(review.question(), review.answer().status(), supplement, review.answer().items());
    }

    private static AgentAnswer result(SearchContext context, List<SubAnswer> answers) {
        var task = context.task();
        return new AgentAnswer(task.assetId(), task.taskId(), task.status() == TaskStatus.PARTIAL, answers);
    }

    enum Supplement { NOT_NEEDED, COMPLETED, FAILED }
    public record SubAnswer(String question, Status status, Supplement supplement, List<Item> items) { }
    public record AgentAnswer(long assetId, String parseTaskId, boolean parsePartial, List<SubAnswer> subquestions) { }
    private record Review(String question, List<Source> sources, Answer answer, String nextQuery) { }
}
