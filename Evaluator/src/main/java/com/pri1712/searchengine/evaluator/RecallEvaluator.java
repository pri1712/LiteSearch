package com.pri1712.searchengine.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.model.ScoredChunk;
import com.pri1712.searchengine.wikiquerying.QueryEngine;

import java.io.*;
import java.util.*;

public class RecallEvaluator {
    private final QueryEngine queryEngine;
    private final String FAILURE_LOG = "retrieval_failures.csv";
    private int TOP_K;
    public RecallEvaluator(QueryEngine engine,int TOP_K) {
        this.queryEngine = engine;
        this.TOP_K = TOP_K;
    }

    public void runEvaluation(String squadJsonPath) throws IOException {
        JsonNode root = new ObjectMapper().readTree(new File(squadJsonPath));
        JsonNode data = root.get("data");

        int totalQuestions = 0;
        int successfulHits = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(FAILURE_LOG))) {
            // CSV Header
            writer.println("Question,Expected_Answer,Top_Result_ID,Top_Result_Snippet,Result_Status");

            for (JsonNode article : data) {
                for (JsonNode paragraph : article.get("paragraphs")) {
                    for (JsonNode qa : paragraph.get("qas")) {
                        String question = qa.get("question").asText();
                        List<String> validAnswers = new ArrayList<>();
                        for (JsonNode ans : qa.get("answers")) {
                            validAnswers.add(ans.get("text").asText().toLowerCase());
                        }

                        List<String> results = queryEngine.start(question);
                        boolean isHit = checkForHit(results, validAnswers);

                        if (isHit) {
                            successfulHits++;
                        } else {
                            continue;
                        }
                        totalQuestions++;
                    }
                }
            }
        }

        double recall = (double) successfulHits / totalQuestions;
        System.out.printf("Recall@%d: %.2f%%%nFailures logged to: %s%n", TOP_K, recall * 100, FAILURE_LOG);
    }

    private boolean checkForHit(List<String> results, List<String> validAnswers) {
        for (String res : results) {
            for (String ans : validAnswers) {
                if (res.contains(ans)) return true;
            }
        }
        return false;
    }
}