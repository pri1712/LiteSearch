package com.pri1712.searchengine.wikiquerying;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.indexreader.IndexData;
import com.pri1712.searchengine.indexreader.IndexReader;
import com.pri1712.searchengine.model.BM25Stats;
import com.pri1712.searchengine.model.ChunkMetaData;
import com.pri1712.searchengine.model.ScoredChunk;
import com.pri1712.searchengine.model.params.RankingParams;
import com.pri1712.searchengine.utils.TextUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class QueryEngine {
    private static final Logger LOGGER = Logger.getLogger(QueryEngine.class.getName());

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);

    private final String invertedIndex;
    private final String docStats;
    private final String tokenIndexOffset;
    private int TOP_K;
    private final int RECORD_SIZE;
    private final double TERM_FREQUENCY_SATURATION; // k1
    private final double DOCUMENT_LENGTH_NORMALIZATION; // b


    private final RandomAccessFile chunkIndexFile;
    private final RandomAccessFile chunkDataFile;
    private IndexReader indexReader;
    private BM25Stats stats;

    public QueryEngine(IndexReader indexReader,String invertedIndex, String docStats, String tokenIndexOffset, int TOP_K,
                       String chunkDataFilePath, String chunkIndexFilePath, int RECORD_SIZE) throws IOException {
        this.invertedIndex = invertedIndex;
        this.docStats = docStats;
        this.tokenIndexOffset = tokenIndexOffset;
        this.TOP_K = TOP_K;
        this.RECORD_SIZE = RECORD_SIZE;
        this.TERM_FREQUENCY_SATURATION = RankingParams.getTERM_FREQUENCY_SATURATION();
        this.DOCUMENT_LENGTH_NORMALIZATION = RankingParams.getDOCUMENT_LENGTH_NORMALIZATION();
        this.indexReader = indexReader;
        this.chunkIndexFile = new RandomAccessFile(chunkIndexFilePath, "r");
        this.chunkDataFile = new RandomAccessFile(chunkDataFilePath, "r");

        Path indexDirectory = Paths.get(invertedIndex);
        Files.list(indexDirectory)
                .filter(p -> p.getFileName().toString().endsWith("_delta_encoded.json"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No inverted index found in directory: " + invertedIndex));
    }

    public void setTopK(int topK) {
        this.TOP_K = topK;
    }
    /**
     * Main entry point for searching.
     * @param query The raw user query string.
     * @return List of the actual text content of the top matching chunks.
     */
    public List<String> start(String query) {
        try {
            if (this.stats == null) {
                initParams();
            }
            List<String> tokens = preprocessQuery(query);
            LOGGER.finest("tokenized query: " + tokens);
            if (tokens.isEmpty()) return Collections.emptyList();

            List<IndexData> queryIndexData = indexReader.readTokenIndex(tokens);

            if (queryIndexData.isEmpty()) {
                LOGGER.info("No matching tokens found in index.");
                return Collections.emptyList();
            }

            Set<Integer> uniqueChunkIds = new HashSet<>();
            for (IndexData data : queryIndexData) {
                LOGGER.finest("chunkIds: " + data.getIds());
                uniqueChunkIds.addAll(data.getIds());
            }

            Map<Integer, ChunkMetaData> metadataMap = fetchMetadataMap(uniqueChunkIds);
            //number of tokens per chunkID, to reward those with higher number of matches.
            Map<Integer, Integer> matchedTerms = new HashMap<>();
            Map<Integer, Double> aggregatedScores = new HashMap<>();

            for (IndexData indexData : queryIndexData) {
                //done per token.
                LOGGER.fine("chunk ID size: " + indexData.getIds().size());
                List<Integer> chunkIds = indexData.getIds();
                List<Integer> freqs = indexData.getFreqs();

                for (int i = 0; i < chunkIds.size(); i++) {
                    int chunkId = chunkIds.get(i);
                    int tf = freqs.get(i);

                    ChunkMetaData meta = metadataMap.get(chunkId);
                    if (meta == null) continue;

                    double score = calculateBM25SingleTerm(meta, tf, chunkIds.size());
                    aggregatedScores.merge(chunkId, score, Double::sum);
                    matchedTerms.merge(chunkId, 1, Integer::sum);
                }
            }
            List<ScoredChunk> allScoredChunks = new ArrayList<>();

            int querySize = tokens.size();
            double avgChunkSize = stats.getAverageChunkSize();

            for (Map.Entry<Integer, Double> entry : aggregatedScores.entrySet()) {
                int chunkId = entry.getKey();
                double baseScore = entry.getValue();
                ChunkMetaData meta = metadataMap.get(chunkId);
                if (meta == null) continue;
                int matched = matchedTerms.getOrDefault(chunkId, 0);

                if (querySize >= 2) {
                    double matchRatio = (double) matched / querySize;
                    if (matchRatio < 0.5) {
                        continue;
                    }
                }
                double coverage = (double) matched / querySize;
                double coverageBoost = 1.0 + (0.3 * coverage);
                double termDensity = (double) matched / meta.getTokenCount();
                double densityBoost = 1.0 + Math.min(0.2, termDensity * 10);
                double proximityBoost = calculateProximityBoostSimple(chunkId,tokens,metadataMap);

                double finalScore = baseScore * coverageBoost * densityBoost * proximityBoost;

                allScoredChunks.add(new ScoredChunk(finalScore, chunkId, meta));
            }
            List<ChunkMetaData> topKMetadata = filterTopKWithDeduplication(allScoredChunks);

            return getChunkData(topKMetadata);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Search execution failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * Reads metadata for a set of chunk IDs in one go to minimize disk head movement logic.
     */
    private Map<Integer, ChunkMetaData> fetchMetadataMap(Set<Integer> chunkIds) throws IOException {
        Map<Integer, ChunkMetaData> map = new HashMap<>();

        LOGGER.fine("RECORD SIZE: " + RECORD_SIZE);
        for (int chunkId : chunkIds) {
            long positionInIndex = (long) chunkId * RECORD_SIZE;

            if (positionInIndex >= chunkIndexFile.length()) {
                LOGGER.warning("ChunkID " + chunkId + " is out of bounds in index file.");
                continue;
            }

            chunkIndexFile.seek(positionInIndex);
            int trueChunkId = chunkIndexFile.readInt();
            long dataOffset = chunkIndexFile.readLong();
            int dataLength = chunkIndexFile.readInt();
            int docId = chunkIndexFile.readInt();
            int tokenCount = chunkIndexFile.readInt();
            LOGGER.finest("CHUNK_ID: " + chunkId +
                    " | DATA FILE OFFSET: " + dataOffset +
                    " | CHUNK LENGTH BYTES: " + dataLength +
                    " | TOKENS: " + tokenCount);
            LOGGER.finest("expected Chunk ID: " + chunkId);
            LOGGER.finest("actual Chunk ID: " + trueChunkId);
            if (trueChunkId != chunkId) {
                LOGGER.severe("Chunk ID being read does not match the chunk ID expected ");
                throw new IllegalStateException("Chunk ID being read does not match the chunk ID expected ");
            }
            map.put(chunkId, new ChunkMetaData(dataOffset, dataLength, docId, tokenCount));
        }
        return map;
    }

    /**
     * Calculates the BM25 score for a single term in a specific chunk.
     */
    private double calculateBM25SingleTerm(ChunkMetaData meta, int tf, int docFreq) {
        long totalDocs = stats.getTotalChunks();
        double avgdl = stats.getAverageChunkSize();
        int docLength = meta.getTokenCount();
        double idf = Math.log(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));
        double num = tf * (TERM_FREQUENCY_SATURATION + 1);
        double denom = tf + TERM_FREQUENCY_SATURATION * (1 - DOCUMENT_LENGTH_NORMALIZATION +
                DOCUMENT_LENGTH_NORMALIZATION * ((double) docLength / avgdl));

        return idf * (num / denom);
    }

    /**
     * Uses a Min-Heap to efficiently select the Top K results.
     */
    private List<ChunkMetaData> filterTOPK(List<ScoredChunk> scoredChunkList) {
        PriorityQueue<ScoredChunk> pq = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredChunk::getScore)
        );

        for (ScoredChunk chunk : scoredChunkList) {
            pq.add(chunk);
            if (pq.size() > TOP_K) {
                pq.poll();
            }
        }
        List<ChunkMetaData> result = new ArrayList<>();
        while (!pq.isEmpty()) {
            ScoredChunk polledElement = pq.poll();
            LOGGER.fine("Score is " + polledElement.getScore());
            result.add(0, polledElement.getChunkMetaData());
        }
        return result;
    }

    /*
    Handles deduplication by recording docId of chunks, allowing only one chunk per DocId in the final results.
     */
    private List<ChunkMetaData> filterTopKWithDeduplication(List<ScoredChunk> scoredChunkList) {
        scoredChunkList.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        List<ChunkMetaData> result = new ArrayList<>();
        Set<Integer> usedDocIds = new HashSet<>();

        for (ScoredChunk chunk : scoredChunkList) {
            LOGGER.finest("Score is " + chunk.getScore() + " for chunkID: " + chunk.getChunkId());
            int docId = chunk.getChunkMetaData().getDocId();
            if (!usedDocIds.contains(docId)) {
                LOGGER.fine("Score: " + chunk.getScore() + " | DocId: " + docId);
                result.add(chunk.getChunkMetaData());
                usedDocIds.add(docId);

                if (result.size() >= TOP_K) {
                    break;
                }
            } else {
                LOGGER.finest("Skipped duplicate docId: " + docId + " (overlap)");
            }
        }

        return result;
    }

    /**
     * Reads the actual text content from the chunks.data file.
     */
    private List<String> getChunkData(List<ChunkMetaData> chunkMetaDataList) throws IOException {
        List<String> chunks = new ArrayList<>();
        for (ChunkMetaData meta : chunkMetaDataList) {
            chunkDataFile.seek(meta.getDataOffset());
            byte[] buffer = new byte[meta.getDataLength()];
            try {
                chunkDataFile.readFully(buffer);
                LOGGER.finest("data read is: " + new String(buffer));
                chunks.add(new String(buffer, StandardCharsets.UTF_8));
            } catch (EOFException e) {
                LOGGER.warning("Unexpected EOF reading chunk at offset: " + meta.getDataOffset());
            }
        }
        return chunks;
    }

    private void initParams() throws IOException {
        File file = new File(docStats);
        if (!file.exists()) {
            throw new FileNotFoundException("Stats file not found: " + docStats);
        }
        this.stats = mapper.readValue(file, BM25Stats.class);
    }

    private List<String> preprocessQuery(String line) {
        return TextUtils.tokenizeQuery(line);
    }

    public void close() throws IOException {
        if (chunkIndexFile != null) chunkIndexFile.close();
        if (chunkDataFile != null) chunkDataFile.close();
    }

    private double calculateProximityBoostSimple(int chunkId, List<String> queryTokens,
                                                 Map<Integer, ChunkMetaData> metadataMap) {
        if (queryTokens.size() <= 1) return 1.0;

        // Without position data, we can only estimate based on:
        // 1. Term frequency - if terms appear multiple times, more likely to be close
        // 2. Chunk size - smaller chunks mean terms are inherently closer

        ChunkMetaData meta = metadataMap.get(chunkId);
        if (meta == null) return 1.0;

        int chunkSize = meta.getTokenCount();

        // Smaller chunks get a small proximity boost
        // Rationale: In a 100-token chunk, 3 terms are likely closer than in a 500-token chunk
        if (chunkSize < 100) {
            return 1.15;
        } else if (chunkSize < 200) {
            return 1.10;
        } else if (chunkSize < 300) {
            return 1.05;
        }

        return 1.0;
    }
}