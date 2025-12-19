package com.pri1712.searchengine.model.params;

public class ChunkParams {
    private static int CHUNK_SIZE;
    private static int CHUNK_OVERLAP;
    private static int MIN_CHUNK_LENGTH;
    private static double ALPHABET_RATIO;
    public ChunkParams(int chunkSize, int chunkOverlap, int minChunkLength, double alphaRatio) {
        CHUNK_SIZE = chunkSize;
        CHUNK_OVERLAP = chunkOverlap;
        MIN_CHUNK_LENGTH = minChunkLength;
        ALPHABET_RATIO = alphaRatio;
    }
    public static int getChunkSize() {
        return CHUNK_SIZE;
    }

    public static int getChunkOverlap() {
        return CHUNK_OVERLAP;
    }

    public static int getMinChunkLength() {
        return MIN_CHUNK_LENGTH;
    }

    public static double getAlphabetRatio() {
        return ALPHABET_RATIO;
    }
}
