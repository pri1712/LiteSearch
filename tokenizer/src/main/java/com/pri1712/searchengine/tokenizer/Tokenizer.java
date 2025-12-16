package com.pri1712.searchengine.tokenizer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.model.TokenizedChunk;
import com.pri1712.searchengine.model.data.Chunk;
import com.pri1712.searchengine.utils.BatchFileWriter;
import com.pri1712.searchengine.parser.CheckpointManager;
import com.pri1712.searchengine.utils.WikiDocument;
import com.pri1712.searchengine.utils.TextUtils;
import com.pri1712.searchengine.model.TokenizedData;

public class Tokenizer {
    private static final int MAX_BATCH_SIZE = 1;

    private static final Logger LOGGER = Logger.getLogger(Tokenizer.class.getName());
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    private int tokenizerBatchCounter = 0;
    private int previousTokenizerBatchCounter;
    private final String tokenizerBatchCheckpointFile = "tokenizerCheckpoint.txt";
    private final CheckpointManager checkpointManager = new CheckpointManager(tokenizerBatchCheckpointFile);
    private final BatchFileWriter batchFileWriter = new BatchFileWriter("data/tokenized-data/");
    private List<TokenizedData> totalTokenizedData = new ArrayList<>();
    private String parsedFilePath;
    private String docStatsPath;
    private long averageDocLength;
    private long numberOfDocuments;
    private final Map<String, Long> perDocLengths = new LinkedHashMap<>();

    public Tokenizer(String parsedFilePath, String docStatsPath) throws RuntimeException {
        this.previousTokenizerBatchCounter = checkpointManager.readCheckpointBatch();
        this.parsedFilePath = parsedFilePath;
        this.docStatsPath = docStatsPath;
    }

    public Tokenizer() {}

    public void tokenizeData() throws IOException {
        Path parsedPath = Paths.get(parsedFilePath);
        Path docStatDir = Paths.get(docStatsPath);
        Path docStatsFile = docStatDir.resolve("doc_stats.json.gz");
        Files.createDirectories(docStatsFile.getParent());

        try (Stream<Path> fileStream = Files.list(parsedPath).filter(f -> f.toString().endsWith(".json.gz"))) {
            fileStream.forEach(parsedFile -> {
                try {
                    processFile(parsedFile);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "IO exception while reading compressed json files", e);
                }
            });
        }

        Path tmp = docStatsFile.resolveSibling("doc_stats.json.gz.tmp");
        try (OutputStream fos = Files.newOutputStream(tmp);
             GZIPOutputStream gos = new GZIPOutputStream(fos);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(gos, StandardCharsets.UTF_8))) {

            Map<String, Object> stats = new HashMap<>();
            stats.put("per_doc_lengths", perDocLengths);
            stats.put("average_doc_length", averageDocLength / Math.max(1, numberOfDocuments));
            stats.put("num_documents", numberOfDocuments);
            mapper.writeValue(bw, stats);
        }
        Files.move(tmp, docStatsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public TokenizedChunk tokenizeChunk(Chunk chunk) throws IOException {
        //tokenize and index the chunk.
        return processChunk(chunk);
    }

    private TokenizedChunk processChunk(Chunk chunk) throws IOException {
        try {
            return TextUtils.tokenizeChunk(chunk);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception while processing chunk", e);
        }
        return null;
    }
    private void processFile(Path parsedFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(parsedFile.toFile());
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader buffRead = new BufferedReader(new InputStreamReader(gis))) {

            List<WikiDocument> jsonDocuments = mapper.readValue(buffRead, new TypeReference<>() {
            });
            for (WikiDocument wikiDocument : jsonDocuments) {
                TokenizedData tokenizedText = TextUtils.tokenizeDocument(TextUtils.normalizeDocument(wikiDocument));
                long docLength = tokenizedText.getLengthTokenizedText() + tokenizedText.getLengthTokenizedTitle();
                perDocLengths.put(wikiDocument.getId(), docLength);
                averageDocLength += docLength;
                numberOfDocuments++;
                totalTokenizedData.add(tokenizedText);
            }
            List<TokenizedData> newTokenizedData = new ArrayList<>(totalTokenizedData);
            totalTokenizedData.clear();
            if (previousTokenizerBatchCounter == -1 || previousTokenizerBatchCounter < tokenizerBatchCounter) {
                batchFileWriter.writeBatch(newTokenizedData, tokenizerBatchCounter);
            }
            checkpointManager.writeCheckpointBatch(tokenizerBatchCounter);
            tokenizerBatchCounter++;
        }
    }
}