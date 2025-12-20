package com.pri1712.searchengine.model.params;

public class ParsingParams {
    private static int MAX_DOCS_TO_PROCESS;
    private static int MAX_BATCH_SIZE;
    public ParsingParams(int MAX_DOCS_TO_PROCESS, int MAX_BATCH_SIZE) {
        ParsingParams.MAX_DOCS_TO_PROCESS = MAX_DOCS_TO_PROCESS;
        ParsingParams.MAX_BATCH_SIZE = MAX_BATCH_SIZE;
    }

    public static int getMaxBatchSize() {
        return MAX_BATCH_SIZE;
    }

    public static int getMaxDocsToProcess() {
        return MAX_DOCS_TO_PROCESS;
    }
}
