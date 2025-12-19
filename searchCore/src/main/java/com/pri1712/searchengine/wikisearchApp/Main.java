package com.pri1712.searchengine.wikisearchApp;

import com.pri1712.searchengine.chunker.Chunker;
import com.pri1712.searchengine.indexwriter.IndexWriter;
import com.pri1712.searchengine.model.params.ChunkParams;
import com.pri1712.searchengine.model.params.QueryParams;
import com.pri1712.searchengine.model.params.RankingParams;
import com.pri1712.searchengine.parser.Parser;
import com.pri1712.searchengine.indexreader.IndexReader;
import com.pri1712.searchengine.wikiquerying.QueryEngine;

import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static final String PARSED_FILE_PATH = "data/parsed-data/";

    private static final String INDEXED_FILE_PATH = "data/inverted-index/";
    private static final String TOKEN_INDEX_OFFSET_PATH = "data/inverted-index/token_index_offset.json.gz";
    private static final String DOC_STATS_PATH = "data/doc-stats/stats.json";
    private static final String CHUNKED_FILE_PATH = "data/chunked-data/";

    private static final int TOP_K = 5;
    private static final int RECORD_SIZE = 24;

    static String parsedFilePath = PARSED_FILE_PATH;

    static String indexedFilePath = INDEXED_FILE_PATH;
    static String tokenIndexOffsetPath = TOKEN_INDEX_OFFSET_PATH;
    static String docStatsPath = DOC_STATS_PATH;
    static String chunkedFilePath = CHUNKED_FILE_PATH;

    private static int CHUNK_SIZE = 512; //in tokens, 1 word = ~0.75 token
    private static int CHUNK_OVERLAP = 55;
    private static int MIN_CHUNK_LENGTH = 20;
    private static double ALPHABET_RATIO = 0.5;

    private static double TERM_FREQUENCY_SATURATION = 1.2;
    private static double DOCUMENT_LENGTH_NORMALIZATION = 0.75;

    private static String chunkDataFilePath = "data/chunked-data/chunked_data.data";
    private static String chunkIndexFilePath = "data/chunked-data/chunked_index.bin";

    public static void main(String[] args) throws IOException {
        long startTime = getStartTime();
        Map<String,String> parsedArgs = parseArgs(args);
        String mode = parsedArgs.getOrDefault("mode", "read");
        String dataPath = parsedArgs.get("data");
        indexedFilePath = parsedArgs.getOrDefault("index", indexedFilePath);
        if ("write".equalsIgnoreCase(mode)) {
            runWritePipeline(dataPath);
            return;
        }
        IndexReader indexReader = openIndexReader(indexedFilePath);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                LOGGER.info("Shutting down, closing index reader...");
                indexReader.close();
                long endTime = getEndTime();
                long elapsedTime = endTime - startTime;
                LOGGER.log(Level.INFO,"Time taken to parse the data : {0} ms",elapsedTime/100000);
                LOGGER.log(Level.INFO,"Memory used: {0} MB", (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/(1024*1024));

            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed closing index reader", e);
            }
        }));

        runReadPipeline(indexReader,indexedFilePath);
        long endTime = getEndTime();
        long elapsedTime = endTime - startTime;
        LOGGER.log(Level.INFO,"Time taken to parse the data : {0} ms",elapsedTime/100000);
        LOGGER.log(Level.INFO,"Memory used: {0} MB", (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/(1024*1024));

    }
    private static Map<String,String> parseArgs(String[] args) {
        Map<String,String> parsedArgs = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                String[] parts = a.substring(2).split("=", 2);
                parsedArgs.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
        }
        return parsedArgs;
    }

    private static void runWritePipeline(String dataPath) {
        try {
            Parser parser = new Parser(dataPath);
            parser.parseData();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            initParams();
            Chunker chunker = new Chunker(parsedFilePath, chunkedFilePath, chunkDataFilePath, chunkIndexFilePath,indexedFilePath, docStatsPath );
            chunker.startChunking();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
//        try {
//            Tokenizer tokenizer = new Tokenizer(parsedFilePath,docStatsPath);
//            LOGGER.info("Tokenizing Wikipedia XML dump file: " + parsedFilePath);
//            tokenizer.tokenizeData();
//
//        } catch (RuntimeException | IOException e) {
//            LOGGER.log(Level.WARNING, e.getMessage());
//            throw new RuntimeException(e);
//        }
        try {
            IndexWriter indexWriter = new IndexWriter(indexedFilePath);
            indexWriter.mergeAllIndexes(indexedFilePath);
        } catch (RuntimeException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void runReadPipeline(IndexReader indexReader, String indexedFilePath) {

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Ready for queries. Type ':reload' to reload index, ':exit' to quit.");
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase(":exit")){
                    return;
                }

                if (line.equalsIgnoreCase(":reload")) {
                    try {
                        indexReader.close();
                        indexReader = openIndexReader(indexedFilePath);
                        System.out.println("Index reloaded.");
                    } catch (IOException e) {
                        System.err.println("Reload failed: " + e.getMessage());
                    }
                    continue;
                }
                try {
                    QueryEngine queryEngine = new QueryEngine(indexReader,indexedFilePath, docStatsPath, tokenIndexOffsetPath, TOP_K, chunkDataFilePath, chunkIndexFilePath, RECORD_SIZE);
                    List<String> relevantChunks = queryEngine.start(line);
                    LOGGER.info("relevant chunks: " + relevantChunks);

                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Query failed", e);
                    System.out.println("Query error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Read loop terminated unexpectedly", e);
        } finally {
            try { indexReader.close(); } catch (IOException ignore) {}
        }
    }

    private static IndexReader openIndexReader(String indexPath) throws IOException {
        Path indexedPath = Paths.get(indexPath);
        LOGGER.info("Opening index at " + indexedPath.toAbsolutePath());
        return new IndexReader(indexedPath.toString(),tokenIndexOffsetPath);
    }

    private static void initParams() {
        new ChunkParams(CHUNK_SIZE, CHUNK_OVERLAP, MIN_CHUNK_LENGTH, ALPHABET_RATIO);
        new RankingParams(TERM_FREQUENCY_SATURATION,DOCUMENT_LENGTH_NORMALIZATION);
        new QueryParams(TOP_K,RECORD_SIZE);
    }
    private static long getStartTime() {
        return System.nanoTime();
    }

    private static long getEndTime() {
        return System.nanoTime();
    }
}