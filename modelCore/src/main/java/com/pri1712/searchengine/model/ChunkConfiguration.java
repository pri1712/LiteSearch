package com.pri1712.searchengine.model;

public class ChunkConfiguration {
    private int chunkSize;
    private int chunkOverlap;
    public ChunkConfiguration(int chunkSize, int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }
}
