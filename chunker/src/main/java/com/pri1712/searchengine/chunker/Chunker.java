package com.pri1712.searchengine.chunker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pri1712.searchengine.utils.WikiDocument;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class Chunker {
    private static final Logger LOGGER = Logger.getLogger(Chunker.class.getName());
    private int chunkSize;
    private int chunkOverlap;
    String parsedFilePath;
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    Path parsedPath;

    public Chunker(int chunkSize, int chunkOverlap, String parsedFilePath) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.parsedFilePath = parsedFilePath;
        parsedPath = Paths.get(parsedFilePath);
    }

    public void startChunking() throws IOException {
        FileInputStream fis = new FileInputStream(parsedFilePath);
        GZIPInputStream gis = new GZIPInputStream(fis);
        BufferedReader br = new BufferedReader(new InputStreamReader(gis));

        //read from the parsed data and then chunk and store them.
        try (Stream<Path> fileStream = Files.list(parsedPath).filter(f -> f.toString().endsWith(".json.gz"))) {
            fileStream.forEach(parsedFile -> {
                try {
                    processFile(parsedFile);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "IO exception while reading compressed json files", e);
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
