package com.pri1712.searchengine.chunker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.utils.BatchFileWriter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class Chunker {
    private static final Logger LOGGER = Logger.getLogger(Chunker.class.getName());
    private int chunkSize;
    private int chunkOverlap;
    private String parsedFilePath;
    private String chunkedFilePath;
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    Path parsedPath;

    private RandomAccessFile chunkDataFile;
    private RandomAccessFile chunkIndexFile;

    public Chunker(int chunkSize, int chunkOverlap, String parsedFilePath, String chunkedFilePath, String chunkDataFilePath, String chunkIndexFilePath) throws IOException {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;

        this.chunkDataFile = new RandomAccessFile(chunkDataFilePath,"rw");
        this.chunkIndexFile = new RandomAccessFile(chunkIndexFilePath,"rw");

        this.parsedFilePath = parsedFilePath;
        parsedPath = Paths.get(parsedFilePath);
        this.chunkedFilePath = chunkedFilePath;
        BatchFileWriter  batchFileWriter = new BatchFileWriter(chunkedFilePath);

        chunkDataFile.seek(chunkDataFile.length());
        chunkIndexFile.seek(chunkIndexFile.length());
    }

    public void startChunking() throws IOException {
        ChunkerEngine chunkerEngine = new ChunkerEngine(chunkSize, chunkOverlap,chunkDataFile,chunkIndexFile);

        //read from the parsed data and then chunk and store them.
        try (Stream<Path> fileStream = Files.list(parsedPath).filter(f -> f.toString().endsWith(".json.gz"))) {
            fileStream.forEach(parsedFile -> {
                try {
                    chunkerEngine.processFile(parsedFile);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error processing file " + parsedFile.toString(), e);
                }
            });
        }
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }
}
