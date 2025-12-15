package com.pri1712.searchengine.model;

import java.util.List;

public class TokenizedChunk {
    private List<String> tokenizedText;
    private String chunkId;
    public  TokenizedChunk(List<String> tokenizedText,String chunkId) {
        this.tokenizedText = tokenizedText;
        this.chunkId = chunkId;
    }
}
