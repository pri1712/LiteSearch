package com.pri1712.searchengine.indexreader;

import java.util.List;

public class IndexData {
    private List<Integer> docIds;
    private List<Integer> freqs;
    private String token;
    public IndexData(List<Integer> docIds, List<Integer> freqs,String token) {
        this.docIds = docIds;
        this.freqs = freqs;
        this.token = token;
    }

    public List<Integer> getDocIds() {
        return docIds;
    }

    public void setDocIds(List<Integer> docIds) {
        this.docIds = docIds;
    }

    public List<Integer> getFreqs() {
        return freqs;
    }

    public void setFreqs(List<Integer> freqs) {
        this.freqs = freqs;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
