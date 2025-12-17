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
        LOGGER.info("token offsets: " + tokenOffsets);
        RandomAccessFile indexRAF = new RandomAccessFile(indexfilePath.toFile(), "r");
        for (var offset : tokenOffsets) {
            if (offset == null || offset < 0) {
                //skip if no tokn offset exists, this happens for common words like 'a'.
                indexList.add(Map.of());
                continue;
            }
            indexRAF.seek(offset);
            String decodedLine = decodeUTF8(indexRAF);
            LOGGER.info(decodedLine);

            if (decodedLine==null || decodedLine.isEmpty()) {
                indexList.add(Map.of());
            }
            Map<Integer,Integer> decodedIndexLine = parsePostingsLine(decodedLine);
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

    private static Map<Integer,Integer> parsePostingsLine(String line) throws JsonProcessingException {
        LOGGER.fine("Parsing postings line: " + line);
        Map<String,Map<String,Integer>> tokenIndexList = mapper.readValue(line, new TypeReference<>() {
        });
        if (tokenIndexList.isEmpty()){
            return Map.of();
        }
        Map<String,Integer> postingList = tokenIndexList.values().iterator().next(); //docid -> freq list.
        if (postingList.isEmpty()){
            return Map.of();
        }
        return decodeDeltaEncoding(postingList);
    }

    private static Map<Integer,Integer> decodeDeltaEncoding(Map<String,Integer> postingList) {
        Map<Integer,Integer> decodedPostings = new LinkedHashMap<>();
        boolean first = true;
        int prevKey = 0;
        for (Map.Entry<String,Integer> entry : postingList.entrySet()) {
            String keyStr = entry.getKey();
            Integer value = entry.getValue() == null ? 0 : entry.getValue();
            int key = 0;
            try {
                key = Integer.parseInt(keyStr);
            } catch (NumberFormatException e) {
                LOGGER.warning(e.getMessage());
            }
            if (first) {
                first = false;
                prevKey = key;
                decodedPostings.put(prevKey,value);
            } else {
                prevKey += key;
                decodedPostings.put(prevKey,value); //decoding delta compressed data.
            }
        }
        return decodedPostings;
    }
}
