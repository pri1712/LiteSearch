package com.pri1712.searchengine.indexreader.decompression;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class IndexDecompression {
    private static final Logger LOGGER = Logger.getLogger(IndexDecompression.class.getName());
    public IndexDecompression() {}
    //figure out how to decompress delta encoded index, probably easiest way would be to maintain a rolling sum.
    //how would this affect time complexity at read time?
    static ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);

    public List<Map<Integer,Integer>> readCompressedIndex(Path indexfilePath, List<Long> tokenOffsets) throws IOException {
        //read data from the given offset, it returns a delta encoded list of docId and freq of the term in that docId.
        List<Map<Integer,Integer>> indexList = new ArrayList<>();
        LOGGER.fine("token offsets: " + tokenOffsets);
        RandomAccessFile indexRAF = new RandomAccessFile(indexfilePath.toFile(), "r");
        for (var offset : tokenOffsets) {
            if (offset == null || offset < 0) {
                //skip if no tokn offset exists, this happens for common words like 'a'.
                indexList.add(Map.of());
                continue;
            }
            indexRAF.seek(offset);
            String decodedLine = decodeUTF8(indexRAF);
            LOGGER.fine(decodedLine);

            if (decodedLine==null || decodedLine.isEmpty()) {
                indexList.add(Map.of());
            }
            Map<Integer,Integer> decodedIndexLine = parsePostingsLine(decodedLine);
            LOGGER.fine(decodedIndexLine.toString());
            indexList.add(decodedIndexLine);
        }
        return indexList;
    }

    private static String decodeUTF8(RandomAccessFile raf) throws IOException {
        //decode UTF8 manually since RAF reads in a different encoding format.
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        int b;
        boolean readData = false;
        while ((b = raf.read()) != -1) {
            readData = true;
            if (b == '\n') break;
            if (b == '\r') {
                long cur = raf.getFilePointer();
                int next = raf.read();
                if (next != '\n') {
                    raf.seek(cur);
                }
                break;
            }
            baos.write(b);
        }
        if (!readData) return null;
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static Map<Integer, Integer> parsePostingsLine(String line) throws JsonProcessingException {
        LOGGER.fine("Parsing postings line: " + line);
        if (line == null || line.trim().isEmpty()) {
            return Map.of();
        }

        Map<String, List<Integer>> tokenIndexList;
        try {
            tokenIndexList = mapper.readValue(line, new TypeReference<Map<String, List<Integer>>>() {});
        } catch (JsonProcessingException e) {
            LOGGER.warning("Failed to parse JSON line: " + line.substring(0, Math.min(line.length(), 50)));
            return Map.of();
        }

        if (tokenIndexList == null || tokenIndexList.isEmpty()){
            return Map.of();
        }
        List<Integer> postingList = tokenIndexList.values().iterator().next();

        if (postingList == null || postingList.isEmpty()){
            return Map.of();
        }
        return decodeDeltaEncoding(postingList);
    }

    private static Map<Integer, Integer> decodeDeltaEncoding(List<Integer> postingList) {
        Map<Integer, Integer> decodedPostings = new LinkedHashMap<>();
        if (postingList == null || postingList.isEmpty()) {
            return decodedPostings;
        }

        int currentDocId = 0;
        // The list is interleaved: [delta1, freq1, delta2, freq2, ...]
        for (int i = 0; i < postingList.size(); i += 2) {
            int delta = postingList.get(i);
            int frequency = (i + 1 < postingList.size()) ? postingList.get(i + 1) : 0;
            currentDocId += delta;
            decodedPostings.put(currentDocId, frequency);
        }
        return decodedPostings;
    }
}
