package com.pri1712.searchengine.chunker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.model.params.ChunkParams;
import com.pri1712.searchengine.utils.BatchFileWriter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class Chunker {
    private static final Logger LOGGER = Logger.getLogger(Chunker.class.getName());
    private int chunkSize;
    private int chunkOverlap;
    private String chunkedFilePath;
    private String indexedFilePath;
    private String docStatsPath;

    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    Path parsedPath;

    private RandomAccessFile chunkDataFile;
    private RandomAccessFile chunkIndexFile;

    public Chunker(String parsedFilePath, String chunkedFilePath, String chunkDataFilePath, String chunkIndexFilePath, String indexedFilePath, String docStatsPath) throws IOException {
        this.chunkSize = ChunkParams.getChunkSize();
        this.chunkOverlap = ChunkParams.getChunkOverlap();
        parsedPath = Paths.get(parsedFilePath);
        this.chunkedFilePath = chunkedFilePath;
        this.indexedFilePath = indexedFilePath;
        this.docStatsPath = docStatsPath;

        BatchFileWriter  batchFileWriter = new BatchFileWriter(chunkedFilePath);

        this.chunkDataFile = new RandomAccessFile(chunkDataFilePath,"rw");
        this.chunkIndexFile = new RandomAccessFile(chunkIndexFilePath,"rw");

        chunkDataFile.seek(chunkDataFile.length());
        chunkIndexFile.seek(chunkIndexFile.length());
    }

    public void startChunking() throws IOException {
        ChunkerEngine chunkerEngine = new ChunkerEngine(chunkDataFile,chunkIndexFile,indexedFilePath,docStatsPath);
        //read from the parsed data and then chunk that data.
        try (Stream<Path> fileStream = Files.list(parsedPath).filter(f -> f.toString().endsWith(".json.gz"))) {
            fileStream.forEach(parsedFile -> {
                try {
                    chunkerEngine.processFile(parsedFile);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error processing file " + parsedFile.toString(), e);
                }
            });
            chunkerEngine.finish();
        }
    }

}
