package com.pri1712.searchengine.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.wikiquerying.QueryEngine;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

public class RecallEvaluator {
    private static Logger LOGGER = Logger.getLogger(RecallEvaluator.class.getName());
    private final QueryEngine queryEngine;
    private final String FAILURE_LOG_DIR = "evaluation_results/";
    private int MAX_DOCS_EVALUATE;

    public RecallEvaluator(QueryEngine engine, int MAX_DOCS) {
        this.queryEngine = engine;
        this.MAX_DOCS_EVALUATE = MAX_DOCS;
        new File(FAILURE_LOG_DIR).mkdirs();
    }

    /**
     * Run evaluation across multiple TOP_K values
     */
    public void runMultiTopKEvaluation(String squadJsonPath, int[] topKValues) throws IOException {
        System.out.println("=== Starting Multi-TOP_K Evaluation ===");
        System.out.println("TOP_K values: " + Arrays.toString(topKValues));
        System.out.println("Max documents to evaluate: " + MAX_DOCS_EVALUATE);
        System.out.println();

        List<QuestionAnswerPair> qaCache = loadQuestionsAndAnswers(squadJsonPath);
        System.out.println("Loaded " + qaCache.size() + " question-answer pairs");
        System.out.println();

        Map<Integer, EvaluationResults> allResults = new LinkedHashMap<>();

        for (int topK : topKValues) {
            queryEngine.setTopK(topK);
            System.out.println("--- Evaluating with TOP_K = " + topK + " ---");
            EvaluationResults results = evaluateWithTopK(qaCache, topK);
            allResults.put(topK, results);
            System.out.println();
        }

        printComparisonTable(allResults);

        saveDetailedResults(allResults);
    }

    /**
     * Run evaluation with a single TOP_K value
     */
    public void runEvaluation(String squadJsonPath, int topK) throws IOException {
        List<QuestionAnswerPair> qaCache = loadQuestionsAndAnswers(squadJsonPath);
        evaluateWithTopK(qaCache, topK);
    }

    /**
     * Load all question-answer pairs from SQuAD JSON
     */
    private List<QuestionAnswerPair> loadQuestionsAndAnswers(String squadJsonPath) throws IOException {
        List<QuestionAnswerPair> qaPairs = new ArrayList<>();
        JsonNode root = new ObjectMapper().readTree(new File(squadJsonPath));
        JsonNode data = root.get("data");

        int docsProcessed = 0;

        outerLoop:
        for (JsonNode article : data) {
            if (docsProcessed >= MAX_DOCS_EVALUATE) {
                break outerLoop;
            }

            String articleTitle = article.get("title").asText();

            for (JsonNode paragraph : article.get("paragraphs")) {
                String context = paragraph.get("context").asText();

                for (JsonNode qa : paragraph.get("qas")) {
                    String question = qa.get("question").asText();
                    String questionId = qa.get("id").asText();

                    List<String> validAnswers = new ArrayList<>();
                    boolean isImpossible = qa.has("is_impossible") && qa.get("is_impossible").asBoolean();

                    if (!isImpossible && qa.has("answers") && !qa.get("answers").isEmpty()) {
                        for (JsonNode ans : qa.get("answers")) {
                            String answerText = ans.get("text").asText().trim();
                            validAnswers.add(normalizeText(answerText));
                        }
                    }

                    if (!validAnswers.isEmpty()) {
                        qaPairs.add(new QuestionAnswerPair(
                                questionId,
                                question,
                                validAnswers,
                                normalizeText(context),
                                articleTitle
                        ));
                    }
                }
            }
            docsProcessed++;
        }

        return qaPairs;
    }

    /**
     * Evaluate with a specific TOP_K value
     */
    private EvaluationResults evaluateWithTopK(List<QuestionAnswerPair> qaPairs, int topK) {
        EvaluationResults results = new EvaluationResults(topK);
        String failureLog = FAILURE_LOG_DIR + "failures_topk" + topK + ".csv";

        try (PrintWriter writer = new PrintWriter(new FileWriter(failureLog))) {
            writer.println("QuestionId,Question,Expected_Answer,Top_Result_Snippet,Rank_Found,Result_Status");

            int processed = 0;
            for (QuestionAnswerPair qa : qaPairs) {
                List<String> retrievedChunks = queryEngine.start(qa.question);

                HitResult hitResult = checkForHit(retrievedChunks, qa.validAnswers, qa.groundTruthContext);

                results.totalQuestions++;

                if (hitResult.isHit) {
                    results.successfulHits++;
                    results.hitRanks.add(hitResult.rank);

                    results.reciprocalRanks.add(1.0 / hitResult.rank);
                } else {
                    results.reciprocalRanks.add(0.0);
                    logFailure(writer, qa, retrievedChunks, hitResult);
                }

                processed++;
                if (processed % 100 == 0) {
                    System.out.printf("Processed %d/%d questions (%.1f%%)...%n",
                            processed, qaPairs.size(), (100.0 * processed / qaPairs.size()));
                }
            }
        } catch (IOException e) {
            LOGGER.severe("Error writing failure log: " + e.getMessage());
        }

        results.calculateMetrics();
        results.printSummary();

        return results;
    }

    /**
     * IMPROVED: Better hit checking with multiple strategies
     */
    private HitResult checkForHit(List<String> retrievedChunks,
                                  List<String> validAnswers,
                                  String groundTruthContext) {
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            return new HitResult(false, -1, "NO_RESULTS");
        }

        for (int rank = 0; rank < retrievedChunks.size(); rank++) {
            String chunk = normalizeText(retrievedChunks.get(rank));

            for (String answer : validAnswers) {
                if (chunk.contains(answer)) {
                    return new HitResult(true, rank + 1, "ANSWER_FOUND");
                }
                if (fuzzyContains(chunk, answer)) {
                    return new HitResult(true, rank + 1, "ANSWER_FOUND_FUZZY");
                }
            }
        }

        String normalizedGroundTruth = normalizeText(groundTruthContext);
        for (int rank = 0; rank < retrievedChunks.size(); rank++) {
            String chunk = normalizeText(retrievedChunks.get(rank));
            if (calculateOverlap(chunk, normalizedGroundTruth) > 0.8) {
                return new HitResult(true, rank + 1, "CONTEXT_MATCH");
            }
        }

        return new HitResult(false, -1, "ANSWER_NOT_FOUND");
    }

    /**
     * Fuzzy string matching to handle minor differences
     */
    private boolean fuzzyContains(String text, String answer) {
        String cleanText = text.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ");
        String cleanAnswer = answer.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ");

        return cleanText.contains(cleanAnswer);
    }

    /**
     * Calculate word overlap between two texts
     */
    private double calculateOverlap(String text1, String text2) {
        Set<String> words1 = new HashSet<>(Arrays.asList(text1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.split("\\s+")));

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * Normalize text for comparison
     */
    private String normalizeText(String text) {
        return text.toLowerCase()
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Log failed retrievals
     */
    private void logFailure(PrintWriter writer, QuestionAnswerPair qa,
                            List<String> results, HitResult hitResult) {
        String topSnippet = "NO_RESULTS_FOUND";
        if (results != null && !results.isEmpty()) {
            String fullText = results.get(0);
            topSnippet = fullText.substring(0, Math.min(150, fullText.length()))
                    .replace(",", ";")
                    .replace("\n", " ");
        }

        String firstAnswer = qa.validAnswers.isEmpty() ? "N/A" : qa.validAnswers.get(0);
        writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\"%n",
                qa.questionId,
                qa.question.replace("\"", "'"),
                firstAnswer.replace("\"", "'"),
                topSnippet.replace("\"", "'"),
                hitResult.rank,
                hitResult.status);
    }

    /**
     * Print comparison table for different TOP_K values
     */
    private void printComparisonTable(Map<Integer, EvaluationResults> allResults) {
        System.out.println("\n=== EVALUATION COMPARISON TABLE ===");
        System.out.println(String.format("%-10s | %-10s | %-10s | %-10s | %-10s",
                "TOP_K", "Recall@K", "Precision@K", "MRR", "Avg Rank"));
        System.out.println("-".repeat(70));

        for (Map.Entry<Integer, EvaluationResults> entry : allResults.entrySet()) {
            int topK = entry.getKey();
            EvaluationResults res = entry.getValue();
            System.out.printf("%-10d | %9.2f%% | %9.2f%% | %10.4f | %10.2f%n",
                    topK, res.recall * 100, res.precision * 100, res.mrr, res.averageRank);
        }
        System.out.println();
    }

    /**
     * Save detailed results to file
     */
    private void saveDetailedResults(Map<Integer, EvaluationResults> allResults) throws IOException {
        String summaryFile = FAILURE_LOG_DIR + "evaluation_summary.csv";
        try (PrintWriter writer = new PrintWriter(new FileWriter(summaryFile))) {
            writer.println("TOP_K,Total_Questions,Successful_Hits,Recall,Precision,MRR,Avg_Rank");

            for (Map.Entry<Integer, EvaluationResults> entry : allResults.entrySet()) {
                int topK = entry.getKey();
                EvaluationResults res = entry.getValue();
                writer.printf("%d,%d,%d,%.4f,%.4f,%.4f,%.2f%n",
                        topK, res.totalQuestions, res.successfulHits,
                        res.recall, res.precision, res.mrr, res.averageRank);
            }
        }
        System.out.println("Detailed results saved to: " + summaryFile);
    }

    // ========== HELPER CLASSES ==========

    /**
     * Stores a question-answer pair with context
     */
    private static class QuestionAnswerPair {
        String questionId;
        String question;
        List<String> validAnswers;
        String groundTruthContext;
        String articleTitle;

        QuestionAnswerPair(String questionId, String question, List<String> validAnswers,
                           String groundTruthContext, String articleTitle) {
            this.questionId = questionId;
            this.question = question;
            this.validAnswers = validAnswers;
            this.groundTruthContext = groundTruthContext;
            this.articleTitle = articleTitle;
        }
    }

    /**
     * Stores hit detection result
     */
    private static class HitResult {
        boolean isHit;
        int rank;
        String status;

        HitResult(boolean isHit, int rank, String status) {
            this.isHit = isHit;
            this.rank = rank;
            this.status = status;
        }
    }

    /**
     * Stores evaluation results for a specific TOP_K
     */
    public static class EvaluationResults {
        int topK;
        int totalQuestions = 0;
        int successfulHits = 0;
        List<Integer> hitRanks = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();

        double recall = 0.0;
        double precision = 0.0;
        double mrr = 0.0;
        double averageRank = 0.0;

        EvaluationResults(int topK) {
            this.topK = topK;
        }

        void calculateMetrics() {
            recall = totalQuestions == 0 ? 0.0 : (double) successfulHits / totalQuestions;
            precision = recall;

            mrr = reciprocalRanks.isEmpty() ? 0.0 :
                    reciprocalRanks.stream().mapToDouble(d -> d).average().orElse(0.0);
            averageRank = hitRanks.isEmpty() ? 0.0 :
                    hitRanks.stream().mapToInt(i -> i).average().orElse(0.0);
        }

        void printSummary() {
            System.out.printf("Total Questions: %d%n", totalQuestions);
            System.out.printf("Successful Hits: %d%n", successfulHits);
            System.out.printf("Recall@%d: %.2f%%%n", topK, recall * 100);
            System.out.printf("Precision@%d: %.2f%%%n", topK, precision * 100);
            System.out.printf("MRR: %.4f%n", mrr);
            System.out.printf("Average Rank (when found): %.2f%n", averageRank);
        }
    }
}