package com.pri1712.searchengine.chunker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.pri1712.searchengine.indexwriter.IndexWriter;
import com.pri1712.searchengine.model.BM25Stats;
import com.pri1712.searchengine.model.ChunkConfiguration;
import com.pri1712.searchengine.model.TokenizedChunk;
import com.pri1712.searchengine.model.data.Chunk;
import com.pri1712.searchengine.utils.WikiDocument;
import com.pri1712.searchengine.tokenizer.Tokenizer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

public class ChunkerEngine {
    private static final Logger LOGGER = Logger.getLogger(ChunkerEngine.class.getName());

    private int chunkSize;
    private int chunkOverlap;
    private final RandomAccessFile chunkDataFile;
    private final RandomAccessFile chunkIndexFile;
    private String indexFilePath;
    private String docStatsFilePath;
    private final BM25Stats stats;
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private int chunkId = 0;

    private long totalChunks = 0;
    private long totalTokens = 0;
    private long averageChunkSize = 0;

    Tokenizer tokenizer = new Tokenizer();
    IndexWriter indexWriter;

    public ChunkerEngine(ChunkConfiguration chunkConfiguration, RandomAccessFile chunkDataFile, RandomAccessFile chunkIndexFile, String indexedFilePath, String docStatsPath) throws IOException {
        this.chunkSize = chunkConfiguration.getChunkSize();
        this.chunkOverlap = chunkConfiguration.getChunkOverlap();
        this.chunkDataFile = chunkDataFile;
        this.chunkIndexFile = chunkIndexFile;
        this.indexFilePath = indexedFilePath;
        this.docStatsFilePath = docStatsPath;
        indexWriter = new IndexWriter(indexFilePath);

        stats = new BM25Stats();
    }

    public void processFile(Path parsedFile) throws IOException {
        String file = parsedFile.toString();
        try (FileInputStream fis = new FileInputStream(parsedFile.toFile());
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader buffRead = new BufferedReader(new InputStreamReader(gis))) {

            List<WikiDocument> jsonDocuments = mapper.readValue(buffRead, new TypeReference<>() {
            });
            int docCount = 0;
            for(WikiDocument wikiDocument : jsonDocuments) {
                //do this per document in a document batch, each document batch is one of the parsed json files.
                docCount++;
                String fullText = wikiDocument.getTitle() + " " + wikiDocument.getText();
                LOGGER.log(Level.INFO, "Full text: {0}", fullText);
                chunkText(fullText, wikiDocument.getId());
            }
            LOGGER.log(Level.INFO, "Processed " + docCount + " documents");
        }
    }

    private void chunkText(String text, String docId) {
        if (text == null || text.isBlank()) return;
        if (!validateText(text)) {
            LOGGER.log(Level.INFO, "Invalid text {0}", text);
            return;
        }
        String[] words = text.split("\\s+");
        int slidingWindowSize = chunkSize - chunkOverlap;

        for (int i = 0; i < words.length; i+=slidingWindowSize) {
            int end = Math.min(words.length, i + chunkSize);
            String[] chunkWords = java.util.Arrays.copyOfRange(words, i, end);
            String chunkText = String.join(" ", chunkWords);
            byte[] chunkBytes = chunkText.getBytes(StandardCharsets.UTF_8);
            long chunkBytesLength = chunkBytes.length;

            try {
                // Tokenize and Index
                TokenizedChunk tokenizedChunk = tokenizer.tokenizeChunk(new Chunk(chunkId,chunkText));
                indexWriter.indexChunks(tokenizedChunk);
                int postProcessedTokenCount = tokenizedChunk.getTokenizedText().size();
                totalTokens += postProcessedTokenCount;
                totalChunks++;
                long dataFilePointer = chunkDataFile.getFilePointer();
                chunkDataFile.write(chunkBytes);

                chunkIndexFile.writeLong(dataFilePointer);
                chunkIndexFile.writeInt((int) chunkBytesLength);
                chunkIndexFile.writeInt((int) Integer.parseInt(docId));
                chunkIndexFile.writeInt(postProcessedTokenCount);

            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, ex.getMessage(), ex);
            }

            chunkId++;
            if (end == words.length) break;
        }
        LOGGER.log(Level.FINE, "Chunk ID {0}", chunkId);
    }

    private boolean validateText(String text) {
        if (text == null || text.isBlank()) return false;
        if (text.length() <= MIN_)
    }

    public void finish() throws IOException {
        if (totalChunks > 0) {
            averageChunkSize = totalTokens / totalChunks;
        }

        stats.setTotalChunks(totalChunks);
        stats.setTotalTokens(totalTokens);
        stats.setAverageChunkSize(averageChunkSize);

        File statsFile = new File(docStatsFilePath);
        if (statsFile.getParentFile() != null) {
            statsFile.getParentFile().mkdirs();
        }
        mapper.writeValue(statsFile, stats);
        indexWriter.close();

        LOGGER.log(Level.FINE, "Indexing finished. Total Chunks: {0}, Avg Length: {1}",
                new Object[]{totalChunks, averageChunkSize});
    }
}