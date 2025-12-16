package com.pri1712.searchengine.wikiquerying;

import com.pri1712.searchengine.indexreader.IndexData;
import com.pri1712.searchengine.utils.TextUtils;
import com.pri1712.searchengine.indexreader.IndexReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryEngine {
    private static final Logger LOGGER = Logger.getLogger(String.valueOf(QueryEngine.class));
    private String invertedIndex;
    private String docStats;
    private String tokenIndexOffset;
    private IndexReader indexReader;
    private Path indexedFilePath;
    private int TOP_K;
    private String chunkDataFilePath;
    private String chunkIndexFilePath;
    public QueryEngine(String invertedIndex, String docStats, String tokenIndexOffset, int TOP_K, String chunkDataFilePath, String chunkIndexFilePath) throws IOException {
        this.invertedIndex = invertedIndex;
        this.docStats = docStats;
        this.tokenIndexOffset = tokenIndexOffset;
        this.TOP_K = TOP_K;
        Path indexDirectory = Paths.get(invertedIndex);
        this.indexedFilePath = Files.list(indexDirectory)
                .filter(p -> p.getFileName().toString().endsWith("_delta_encoded.json"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("no inverted index found"));
        this.chunkDataFilePath = chunkDataFilePath;
        this.chunkIndexFilePath = chunkIndexFilePath;
    }

    public void start(String line) throws IOException {
        //tokenize and normalize the query
        List<String> tokens = preprocessQuery(line);
        this.indexReader = new IndexReader(invertedIndex,tokenIndexOffset);
        //returns a list of {chunkId,frequencies,token} objects.
        List<IndexData> queryIndexData = indexReader.readTokenIndex(tokens);
        //from the chunkID now we have to access the chunk metadata to get to the actual chunk.
        String firstToken = queryIndexData.get(0).getToken();
        List<Integer> firstChunkIDList = queryIndexData.get(0).getIds();
        List<Integer> firstFreqList = queryIndexData.get(0).getFreqs();
        LOGGER.fine("First Token: " + firstToken);
        LOGGER.fine("First Chunk ID: " + firstChunkIDList);
        LOGGER.fine("First Freq: " + firstFreqList);
        try {
            getChunk(firstToken,firstChunkIDList,firstFreqList);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,e.getMessage(),e);
        }

    }

    public List<String> preprocessQuery(String line) throws IOException {
        List<String> tokens = Arrays.asList(line.split(" "));
        return TextUtils.tokenizeQuery(tokens);
    }

    private void getChunk(String token,List<Integer> firstChunkIDList,List<Integer> firstFreqList) throws IOException {
        try {
            getChunkMetadata(firstChunkIDList);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,e.getMessage(),e);
        }
    }

    private void getChunkMetadata(List<Integer> chunkIdList) throws IOException {

    }
}
