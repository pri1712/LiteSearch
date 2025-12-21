package com.pri1712.searchengine.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.wikiquerying.QueryEngine;

import java.io.*;
import java.util.*;

public class RecallEvaluator {
    private final QueryEngine queryEngine;
    private final String FAILURE_LOG = "retrieval_failures.csv";
    private int TOP_K;
    private int MAX_DOCS_EVALUATE;
    public RecallEvaluator(QueryEngine engine,int TOP_K, int MAX_DOCS) {
        this.queryEngine = engine;
        this.TOP_K = TOP_K;
        this.MAX_DOCS_EVALUATE = MAX_DOCS;
    }

    public void runEvaluation(String squadJsonPath) throws IOException {
        JsonNode root = new ObjectMapper().readTree(new File(squadJsonPath));
        JsonNode data = root.get("data");
        int totalDocsProcessed = 0;
        int totalQuestions = 0;
        int successfulHits = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(FAILURE_LOG))) {
            writer.println("Question,Expected_Answer,Top_Result_Snippet,Result_Status");
            outerLoop:
            for (JsonNode article : data) {
                if (totalDocsProcessed >= MAX_DOCS_EVALUATE) {
                    System.out.println("Reached MAX_DOCS limit: " + MAX_DOCS_EVALUATE);
                    break outerLoop;
                }

                for (JsonNode paragraph : article.get("paragraphs")) {
                    for (JsonNode qa : paragraph.get("qas")) {

                        String question = qa.get("question").asText();
                        List<String> validAnswers = new ArrayList<>();
                        if (!qa.has("answers") || qa.get("answers").isEmpty()) continue;

                        for (JsonNode ans : qa.get("answers")) {
                            validAnswers.add(ans.get("text").asText().toLowerCase());
                        }

                        List<String> results = queryEngine.start(question);
                        totalQuestions++;

                        boolean isHit = checkForHit(results, validAnswers);

                        if (isHit) {
                            successfulHits++;
                        } else {
                            logFailure(writer, question, validAnswers, results);
                        }

                        if (totalQuestions % 100 == 0) {
                            System.out.println("Processed " + totalQuestions + " questions...");
                        }
                    }
                }
                totalDocsProcessed++;
            }
        }

        double recall = (totalQuestions == 0) ? 0 : (double) successfulHits / totalQuestions;
        System.out.printf("%n--- Evaluation Complete (Processed %d docs) ---%n", totalDocsProcessed);
        System.out.printf("Total Questions: %d%n", totalQuestions);
        System.out.printf("Successful Hits: %d%n", successfulHits);
        System.out.printf("Recall@%d: %.2f%%%n", TOP_K, recall * 100);
        System.out.printf("Failures logged to: %s%n", FAILURE_LOG);
    }

    private void logFailure(PrintWriter writer, String q, List<String> answers, List<String> results) {
        String topSnippet = "NO_RESULTS_FOUND";
        if (results != null && !results.isEmpty()) {
            String fullText = results.get(0);
            topSnippet = fullText.substring(0, Math.min(100, fullText.length()))
                    .replace(",", " ")
                    .replace("\n", " ");
        }

        String firstAnswer = answers.isEmpty() ? "N/A" : answers.get(0).replace(",", " ");
        writer.printf("\"%s\",\"%s\",\"%s\",\"FAIL\"%n",
                q.replace("\"", "'"),
                firstAnswer.replace("\"", "'"),
                topSnippet.replace("\"", "'"));
    }

    private boolean checkForHit(List<String> results, List<String> validAnswers) {
        for (String res : results) {
            String resLowerCase = res.trim().toLowerCase();
            for (String ans : validAnswers) {
                if (resLowerCase.contains(ans)) return true;
            }
        }
        return false;
    }
}