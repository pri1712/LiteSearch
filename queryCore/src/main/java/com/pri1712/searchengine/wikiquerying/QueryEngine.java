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
import java.util.logging.Logger;

public class QueryEngine {
    private static final Logger LOGGER = Logger.getLogger(String.valueOf(QueryEngine.class));
    private String invertedIndex;
    private String docStats;
    private String tokenIndexOffset;
    private IndexReader indexReader;
    private Path indexedFilePath;
    public QueryEngine(String invertedIndex, String docStats, String tokenIndexOffset) throws IOException {
        this.invertedIndex = invertedIndex;
        this.docStats = docStats;
        this.tokenIndexOffset = tokenIndexOffset;
        Path directory = Paths.get(invertedIndex);
        this.indexedFilePath = Files.list(directory)
                .filter(p -> p.getFileName().toString().endsWith("_delta_encoded.json"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("no inverted index found"));
    }

    public void start(String line) throws IOException {
        //tokenize and normalize the query
        List<String> tokens = preprocessQuery(line);
        this.indexReader = new IndexReader(invertedIndex,tokenIndexOffset);
        List<IndexData> queryIndexData = indexReader.readTokenIndex(tokens);
        //now we have the doc IDs and freq of the token in eaach of those,how do we do IR now?
    }

    public List<String> preprocessQuery(String line) throws IOException {
        List<String> tokens = Arrays.asList(line.split(" "));
        return TextUtils.tokenizeQuery(tokens);
    }
}
