package com.pri1712.searchengine.model;

public class ScoredChunk {
    private double score;
    private int chunkId;
    private ChunkMetaData chunkMetaData;
    public ScoredChunk(double score, int chunkId, ChunkMetaData chunkMetaData) {
        this.score = score;
        this.chunkId = chunkId;
        this.chunkMetaData = chunkMetaData;
    }

    public ChunkMetaData getChunkMetaData() {
        return chunkMetaData;
    }

    public String getChunkId() {
        return chunkId;
    }

    public double getScore() {
        return score;
    }
}
