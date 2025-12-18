package com.pri1712.searchengine.model.params;

public class QueryParams {
    private static int TOP_K;
    private static int RECORD_SIZE;
    public QueryParams(int TOP_K, int RECORD_SIZE) {
        QueryParams.TOP_K = TOP_K;
        QueryParams.RECORD_SIZE = RECORD_SIZE;
    }

    public int getTOP_K() {
        return TOP_K;
    }
    public int getRECORD_SIZE() {
        return RECORD_SIZE;
    }
}
