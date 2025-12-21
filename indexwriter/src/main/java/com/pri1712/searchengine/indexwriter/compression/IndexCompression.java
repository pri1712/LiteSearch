package com.pri1712.searchengine.indexwriter.compression;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.indexreader.TokenOffsetData;
import com.pri1712.searchengine.utils.CountingOutputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class IndexCompression {

    private static Logger LOGGER = Logger.getLogger(IndexCompression.class.getName());

    public IndexCompression() throws IOException {}
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    CountingOutputStream counter = new CountingOutputStream();
    JsonGenerator gen = mapper.getFactory().createGenerator(counter, JsonEncoding.UTF8);

    public void deltaEncode(Path inputFilePath, Path tokenIndexOffsetPath) {
        Path outputFilePath = Paths.get(inputFilePath.getParent().toString(),
                inputFilePath.getFileName().toString().replace(".json.gz", "_delta_encoded.json")
        );
        long byteOffset = 0;
        Map<String,Long> tokenOffsets = new LinkedHashMap<>();

        try (FileInputStream fis = new FileInputStream(inputFilePath.toFile())) {
            GZIPInputStream gis = new GZIPInputStream(fis);
            BufferedReader br = new BufferedReader(new InputStreamReader(gis,StandardCharsets.UTF_8));
            FileOutputStream fos = new FileOutputStream(outputFilePath.toFile());
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            String line;
            while ((line = br.readLine()) != null){
                Map<String, Map<Integer,Integer>> index = mapper.readValue(line, new TypeReference<>() {});

                for (var e : index.entrySet()) {
                    String token = e.getKey();
                    Map<Integer,Integer> docFreqMap = e.getValue();
                    List<Integer> docIDs = new ArrayList<>(docFreqMap.keySet());
                    if (docIDs.isEmpty()) continue;
                    List<Integer> flatDeltaList = new ArrayList<>(docIDs.size() * 2);

                    int firstDocID = docIDs.get(0);
                    int prevDocID = firstDocID;

                    flatDeltaList.add(firstDocID);
                    flatDeltaList.add(docFreqMap.get(firstDocID));

                    for (int i = 1; i < docIDs.size(); i++) {
                        int currentDocID = docIDs.get(i);
                        int delta = currentDocID - prevDocID;

                        flatDeltaList.add(delta);
                        flatDeltaList.add(docFreqMap.get(currentDocID));

                        prevDocID = currentDocID;
                    }
                    byte[] jsonBytes = mapper.writeValueAsBytes(Map.of(token, flatDeltaList));
                    tokenOffsets.put(token, byteOffset);
                    bos.write(jsonBytes);
                    bos.write('\n');
                    byteOffset += jsonBytes.length + 1L;
                }
            }
            FileOutputStream offsetOutputStream = new FileOutputStream(tokenIndexOffsetPath.toFile());
            GZIPOutputStream gos2 = new GZIPOutputStream(offsetOutputStream);
            OutputStreamWriter osw = new OutputStreamWriter(gos2, StandardCharsets.UTF_8);
            for (Map.Entry<String,Long> entry : tokenOffsets.entrySet()) {
                TokenOffsetData tokenOffsetData = new TokenOffsetData(entry.getKey(), entry.getValue());
                mapper.writeValue(osw, tokenOffsetData);
                osw.write("\n");;
            }
            LOGGER.fine("Wrote token offsets to " + tokenIndexOffsetPath);
            osw.flush();
            osw.close();
            gos2.finish();
            gos2.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not open input file " + inputFilePath.toString(), e);
        }
    }

    public void deltaEncode(Path inputFilePath) {
        Path outputFilePath = Paths.get(inputFilePath.getParent().toString(),
                inputFilePath.getFileName().toString().replace(".json.gz", "_delta_encoded.json")
        );
        long byteOffset = 0;

        try (FileInputStream fis = new FileInputStream(inputFilePath.toFile())) {
            GZIPInputStream gis = new GZIPInputStream(fis);
            BufferedReader br = new BufferedReader(new InputStreamReader(gis,StandardCharsets.UTF_8));
            FileOutputStream fos = new FileOutputStream(outputFilePath.toFile());
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            String line;
            while ((line = br.readLine()) != null){
                Map<String, Map<Integer,Integer>> index = mapper.readValue(line, new TypeReference<>() {});
                for (var e : index.entrySet()) {
                    String token = e.getKey();
                    Map<Integer,Integer> docFreqMap = e.getValue();
                    Map<Integer, Integer> deltaMap = new LinkedHashMap<>();
                    List<Integer> docIDs = new ArrayList<>(docFreqMap.keySet());
                    int firstDocID = docIDs.get(0);
                    int prevDocID = firstDocID;
                    deltaMap.put(firstDocID, docFreqMap.get(firstDocID));
                    for (int i = 1; i<docIDs.size(); i++) {
                        int currentDocID = docIDs.get(i);
                        int delta = currentDocID - prevDocID;
                        deltaMap.put(delta, docFreqMap.get(currentDocID));
                        prevDocID = currentDocID;
                    }
                    byte[] jsonBytes = mapper.writeValueAsBytes(Map.of(token,deltaMap));
                    bos.write(jsonBytes);
                    bos.write('\n');
                    byteOffset += jsonBytes.length + 1L;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not open input file " + inputFilePath.toString(), e);
        }
    }
}
