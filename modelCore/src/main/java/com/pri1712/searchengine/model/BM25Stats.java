package com.pri1712.searchengine.model;

public class BM25Stats {
    private long totalChunks;
    private long totalTokens;
    private long averageChunkSize;
    public BM25Stats() {}

    public BM25Stats(long totalChunks, long totalTokens, long averageChunkSize) {
        this.totalChunks = totalChunks;
        this.totalTokens = totalTokens;
        this.averageChunkSize = averageChunkSize;
    }

}
