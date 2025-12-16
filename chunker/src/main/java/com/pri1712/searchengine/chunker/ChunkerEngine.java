package com.pri1712.searchengine.chunker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pri1712.searchengine.indexwriter.IndexWriter;
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
    private RandomAccessFile chunkDataFile;
    private RandomAccessFile chunkIndexFile;
    private String indexFilePath;

    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    private int chunkId = 0;

    Tokenizer tokenizer = new Tokenizer();
    IndexWriter indexWriter;

    public ChunkerEngine(int chunkSize, int chunkOverlap, RandomAccessFile chunkDataFile, RandomAccessFile chunkIndexFile, String indexedFilePath) throws IOException {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.chunkDataFile = chunkDataFile;
        this.chunkIndexFile = chunkIndexFile;
        this.indexFilePath = indexedFilePath;
        indexWriter = new IndexWriter(indexFilePath);
    }

    public void processFile(Path parsedFile) throws IOException {
        //process the file here. chunk it based on the params and store the chunked data in chunkId -> file offset manner.
        String file = parsedFile.toString();
        try (FileInputStream fis = new FileInputStream(parsedFile.toFile());
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader buffRead = new BufferedReader(new InputStreamReader(gis))) {

            List<WikiDocument> jsonDocuments = mapper.readValue(buffRead, new TypeReference<>() {
            });
            for(WikiDocument wikiDocument : jsonDocuments) {
                chunkText(wikiDocument.getTitle(), wikiDocument.getId());
                chunkText(wikiDocument.getText(), wikiDocument.getId());
            }
        }
    }

    private void chunkText(String text, String docId) {
        //actual chunking happens here.
        String[] words = text.split("\\s+");
        int slidingWindowSize = chunkSize - chunkOverlap;

        for (int i = 0; i < words.length; i+=slidingWindowSize) {
            int end = Math.min(words.length, i + slidingWindowSize);
            String[] chunkWords = java.util.Arrays.copyOfRange(words, i, end);
            String chunkText = String.join(" ", chunkWords);
            byte[] chunkBytes = chunkText.getBytes(StandardCharsets.UTF_8);
            long chunkBytesLength = chunkBytes.length;
            try {
                TokenizedChunk tokenizedChunk = tokenizer.tokenizeChunk(new Chunk(chunkId,chunkText));
                indexWriter.indexChunks(tokenizedChunk);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, ex.getMessage(), ex);
            }
            try {
                long dataFilePointer = chunkDataFile.getFilePointer();
                chunkDataFile.write(chunkBytes);
                chunkIndexFile.writeLong(dataFilePointer);
                chunkIndexFile.writeInt((int) chunkBytesLength);
                chunkIndexFile.writeInt((int) Integer.parseInt(docId));

            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error reading chunk data file pointer", e);
            }
            chunkId++;
        }
        LOGGER.log(Level.FINE, "Chunk ID {0}", chunkId);
    }
}
