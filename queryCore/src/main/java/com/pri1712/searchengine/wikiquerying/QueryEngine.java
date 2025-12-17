package com.pri1712.searchengine.wikiquerying;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.indexreader.IndexData;
import com.pri1712.searchengine.model.BM25Stats;
import com.pri1712.searchengine.model.ScoredChunk;
import com.pri1712.searchengine.model.TokenizedChunk;
import com.pri1712.searchengine.utils.TextUtils;
import com.pri1712.searchengine.indexreader.IndexReader;
import com.pri1712.searchengine.model.ChunkMetaData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Math.min;

public class QueryEngine {
    private static final Logger LOGGER = Logger.getLogger(String.valueOf(QueryEngine.class));
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    private String invertedIndex;
    private String docStats;
    private String tokenIndexOffset;
    private IndexReader indexReader;
    private Path indexedFilePath;
    private final int RECORD_SIZE;
    private final RandomAccessFile chunkIndexFile;
    private final RandomAccessFile chunkDataFile;
    private BM25Stats stats;

    private final int TOP_K;
    private final double TERM_FREQUENCY_SATURATION;
    private final double DOCUMENT_LENGTH_NORMALIZATION;

    public QueryEngine(String invertedIndex, String docStats, String tokenIndexOffset, int TOP_K, String chunkDataFilePath, String chunkIndexFilePath, int RECORD_SIZE, double TERM_FREQUENCY_SATURATION, double DOCUMENT_LENGTH_NORMALIZATION) throws IOException {
        this.invertedIndex = invertedIndex;
        this.docStats = docStats;
        this.tokenIndexOffset = tokenIndexOffset;
        this.TOP_K = TOP_K;
        Path indexDirectory = Paths.get(invertedIndex);
        this.indexedFilePath = Files.list(indexDirectory)
                .filter(p -> p.getFileName().toString().endsWith("_delta_encoded.json"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("no inverted index found"));
        this.RECORD_SIZE = RECORD_SIZE;
        this.chunkIndexFile = new RandomAccessFile(chunkIndexFilePath, "r");
        this.chunkDataFile = new RandomAccessFile(chunkDataFilePath, "r");
        this.TERM_FREQUENCY_SATURATION = TERM_FREQUENCY_SATURATION;
        this.DOCUMENT_LENGTH_NORMALIZATION = DOCUMENT_LENGTH_NORMALIZATION;
    }

    public void start(String line) throws IOException {
        //tokenize and normalize the query
        try {
            initParams();
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.WARNING, "File not found", e);
        } catch (JsonProcessingException e) {
            LOGGER.warning("Json processing exception in QueryEngine");
        }
        List<String> tokens = preprocessQuery(line);
        this.indexReader = new IndexReader(invertedIndex,tokenIndexOffset);
        //returns a list of {chunkId,frequencies,token} objects.
        List<IndexData> queryIndexData = indexReader.readTokenIndex(tokens);
        //from the chunkID now we have to access the chunk metadata to get to the actual chunk.
        for (IndexData indexData : queryIndexData) {
            List<Integer> chunkIDList = indexData.getIds();
            List<Integer> freqList = indexData.getFreqs();
            String token = indexData.getToken();
            try {
                getChunk(token,chunkIDList,freqList);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,e.getMessage(),e);
            }
        }
    }

    private void initParams() throws FileNotFoundException, JsonProcessingException {
        File file = new File(docStats);
        if (!file.exists()) {
            LOGGER.log(Level.WARNING, "docStats file does not exist");
            throw new FileNotFoundException("docStats file does not exist");
        }
        this.stats = mapper.readValue(docStats,BM25Stats.class);
    }
    public List<String> preprocessQuery(String line) throws IOException {
        List<String> tokens = Arrays.asList(line.split(" "));
        return TextUtils.tokenizeQuery(tokens);
    }

    private void getChunk(String token,List<Integer> chunkIDList,List<Integer> freqList) throws IOException {
        try {
            List<ChunkMetaData> chunkMetadata = getChunkMetadata(chunkIDList, freqList);
            List<String> chunks = getChunkData(chunkMetadata);
            LOGGER.fine("chunkMetadata data offset: " + chunkMetadata.get(0).getDataOffset());
            LOGGER.fine("chunkMetadata data length: " + chunkMetadata.get(0).getDataLength());
            LOGGER.info("first chunk :" + chunks.get(0));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,e.getMessage(),e);
        }
    }

    /***
     *
     * @param chunkIDList
     * @param freqList
     * @return a list of chunk Metadata objects for all the chunkIDs in the param chunkIDList.
     * @throws IOException
     */
    private List<ChunkMetaData> getChunkMetadata(List<Integer> chunkIDList,List<Integer> freqList) throws IOException {
        //read the chunk_index.bin file, get the length, and offset in the data file
        List<ChunkMetaData> chunkMetaData = new ArrayList<>();
        for (int currentChunkID : chunkIDList) {
            //get the details for the top k in the chunk ID list.
            long positionInIndex = (long) currentChunkID * RECORD_SIZE;
            if (positionInIndex >= chunkIndexFile.length()) {
                LOGGER.warning("Unable to access the metadata in the chunk index due to mismatch in sizing");
                continue;
            }
            chunkIndexFile.seek(positionInIndex);
            long dataOffset = chunkIndexFile.readLong();
            int dataLength = chunkIndexFile.readInt();
            int docId = chunkIndexFile.readInt();
            int tokenCount = chunkIndexFile.readInt();
            chunkMetaData.add(new ChunkMetaData(dataOffset, dataLength, docId, tokenCount));
        }
        List<ScoredChunk> scoredChunkList = rankBM25(chunkMetaData,freqList,chunkIDList);
        List<ChunkMetaData> filteredChunkMetaData = new ArrayList<>();
        return filteredChunkMetaData;
    }

    /***
     *
     * @param chunkMetaData
     * @param freqList
     * @param chunkIDList
     * @return returns a list of scoredChunk objects, these contain the score for the chunk too with respect to the token being queried.
     */
    private List<ScoredChunk> rankBM25(List<ChunkMetaData> chunkMetaData,List<Integer> freqList,List<Integer> chunkIDList) {
        List<ScoredChunk> scoredChunkList = new ArrayList<>();
        for (int i =0;i<chunkMetaData.size();i++) {
            //score each of the entries in the chunkmetadata list.
            int tokenFrequency = freqList.get(i);
//            long totalTokenCount = stats.getTotalTokens();
            long totalChunkCount = stats.getTotalChunks();
            int postListSize = chunkIDList.size();
            long averageChunkSize = stats.getAverageChunkSize();
            int currentChunkID = chunkIDList.get(i);
            ChunkMetaData data = chunkMetaData.get(i);
            ScoredChunk scoredChunk = scoreChunks(data,tokenFrequency, totalChunkCount, postListSize, averageChunkSize,currentChunkID);
            scoredChunkList.add(scoredChunk);
        }
        return scoredChunkList;
    }


    private ScoredChunk scoreChunks(ChunkMetaData data,int tokenFrequency, long totalChunkCount, int postingListSize, long averageChunkSize, int currentChunkID) {
        double idf = Math.log(1.0 + (totalChunkCount - postingListSize + 0.5) / (postingListSize + 0.5));
        int chunkLength = data.getTokenCount();
        double num = tokenFrequency * (TERM_FREQUENCY_SATURATION + 1);
        double denom = tokenFrequency + TERM_FREQUENCY_SATURATION * (1 - DOCUMENT_LENGTH_NORMALIZATION + DOCUMENT_LENGTH_NORMALIZATION * ((double) chunkLength / averageChunkSize));

        double score = idf * (num / denom);
        ScoredChunk scoredChunk = new ScoredChunk(score,currentChunkID,data);
        return scoredChunk;
    }
    private List<String> getChunkData(List<ChunkMetaData> chunkMetaData) throws IOException {
        List<String> chunks = new ArrayList<>();
        for (ChunkMetaData chunkMetaDataData : chunkMetaData) {
            long dataOffset = chunkMetaDataData.getDataOffset();
            int dataLength = chunkMetaDataData.getDataLength();
            chunkDataFile.seek(dataOffset);
            byte[] buffer = new byte[dataLength];
            //read data into the buffer.
            try {
                chunkDataFile.readFully(buffer);
            } catch (EOFException e) {
                LOGGER.warning(String.format("Unable to read all the chunk data: " + e.getMessage()));
            }
            String chunkText = new String(buffer, StandardCharsets.UTF_8);
            chunks.add(chunkText);
        }
        return chunks;
    }
}
