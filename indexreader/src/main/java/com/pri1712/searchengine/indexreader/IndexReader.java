package com.pri1712.searchengine.indexreader;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.indexreader.decompression.IndexDecompression;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;


public class IndexReader {
    private static final Logger LOGGER = Logger.getLogger(String.valueOf(IndexReader.class));
    private Path indexedFilePath;
    private Path indexTokenOffsetFilePath;
    private Map<String,Long> tokenOffsetMap;
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    IndexDecompression indexDecompression = new IndexDecompression();
   List<Integer> docIds = new ArrayList<>();
   List<Integer> freqs = new ArrayList<>();
    public IndexReader(String indexedFilePath,String indexTokenOffsetFilePath) {
        Path dir = Paths.get(indexedFilePath);
        try {
            this.indexedFilePath = Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith("_delta_encoded.json"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("no inverted index found"));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,e.getMessage(),e);
        }
        this.indexTokenOffsetFilePath = Paths.get(indexTokenOffsetFilePath);
        tokenOffsetMap = new HashMap<>();
        try {
            loadTokenMapInMemory(indexTokenOffsetFilePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,e.getMessage(),e);
        }
    }

    public IndexReader() {}

    private void loadTokenMapInMemory(String indexTokenOffsetFilePath) throws IOException {
        FileInputStream fis = new FileInputStream(indexTokenOffsetFilePath.toString());
        GZIPInputStream gis = new GZIPInputStream(fis);
        BufferedReader buffRead = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8));
        String line;
        while ((line = buffRead.readLine()) != null) {
            TokenOffsetData tokenOffsetData = mapper.readValue(line, TokenOffsetData.class);
            tokenOffsetMap.put(tokenOffsetData.getToken(), tokenOffsetData.getOffset());//storing token to offset in memory in a map.
        }
    }

    public IndexData readTokenIndex(String token) throws IOException {
        Long tokenOffset = tokenOffsetMap.get(token);
        List<Long> tokenOffsets = new ArrayList<>();
        addTokenOffset(tokenOffset,tokenOffsets);
        List<Map<Integer,Integer>> decompressedPostingList = indexDecompression.readCompressedIndex(indexedFilePath,tokenOffsets);
        LOGGER.fine("decompressed posting list: " + decompressedPostingList);
        LOGGER.info("Decompressed posting list size: " + decompressedPostingList.size());
        Map<Integer,Integer> postingMap = decompressedPostingList.get(0);
        for (var entry : postingMap.entrySet()) {
            docIds.add(entry.getKey());
            freqs.add(entry.getValue());
        }
        return new IndexData(docIds,freqs,token);
    }

    //reads the token offsets and the tokens, and returns a list of {freq,chunkID,token} objects for each token.
    public List<IndexData> readTokenIndex(List<String> tokens) throws IOException {
        List<IndexData> indexDataList = new ArrayList<>();
        List<String> validTokens = new ArrayList<>();
        List<Long> validOffsets = new ArrayList<>();

        for (String token : tokens) {
            Long offset = tokenOffsetMap.get(token);
            if (offset != null && offset >= 0) {
                validTokens.add(token);
                validOffsets.add(offset);
            }
        }

        if (validOffsets.isEmpty()) {
            return indexDataList;
        }

        List<Map<Integer, Integer>> allPostings = indexDecompression.readCompressedIndex(indexedFilePath, validOffsets);

        for (int i = 0; i < validTokens.size(); i++) {
            String token = validTokens.get(i);
            Map<Integer, Integer> postingsMap = allPostings.get(i);
            List<Integer> chunkIds = new ArrayList<>(postingsMap.keySet());
            List<Integer> freqs = new ArrayList<>(postingsMap.values());
            indexDataList.add(new IndexData(chunkIds, freqs, token));
        }

        return indexDataList;
    }
    private void addTokenOffset(Long offset,List<Long> tokenOffsets) {
        tokenOffsets.add(offset);
    }

    public void close() throws IOException {
        LOGGER.info("close");
    }


}
