package com.pri1712.searchengine.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.model.ParsedDocument;
import com.pri1712.searchengine.model.params.ParsingParams;
import com.pri1712.searchengine.utils.BatchFileWriter;
import com.pri1712.searchengine.utils.TextUtils;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/***
 * Class for parsing squad json format.
 */
public class SquadParser implements DocumentParser {
    private static final Logger LOGGER = Logger.getLogger(SquadParser.class.getName());

    private final String dataFilePath;
    private final String outputDir;
    private final String parserBatchCheckpointFile = "parserCheckpoint.txt";
    private final boolean enableCheckpoint;

    private int MAX_DOCS_TO_PROCESS;
    private int MAX_BATCH_SIZE;
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);

    public SquadParser(String dataFilePath, boolean enableCheckpoint, String outputDir) {
        this.dataFilePath = dataFilePath;
        this.enableCheckpoint = enableCheckpoint;
        this.outputDir = outputDir;

        this.MAX_DOCS_TO_PROCESS = ParsingParams.getMaxDocsToProcess();
        LOGGER.info("Max docs to process: " + MAX_DOCS_TO_PROCESS);

        this.MAX_BATCH_SIZE = ParsingParams.getMaxBatchSize();
        LOGGER.info("Max batch size: " + MAX_BATCH_SIZE);
    }

    @Override
    public void parse() throws IOException {
        List<ParsedDocument> writeBuffer = new ArrayList<>();

        if (dataFilePath == null || dataFilePath.isEmpty()) {
            throw new FileNotFoundException("Data file path is null or empty");
        }
        BatchFileWriter batchWriter = new BatchFileWriter(outputDir);
        CheckpointManager checkpointManager = null;
        int previousParseBatchCounter = -1;
        int parseBatchCounter = 0;

        if (enableCheckpoint) {
            checkpointManager = new CheckpointManager(parserBatchCheckpointFile);
            previousParseBatchCounter = checkpointManager.readCheckpointBatch();
            LOGGER.info("Resuming from previous parse batch count: " + previousParseBatchCounter);
        }

        try (Reader reader = new FileReader(dataFilePath)) {
            JsonNode root = mapper.readTree(reader);
            JsonNode data = root.get("data");
            int docCounter = 0;
            for (JsonNode article : data) {
                String title = article.get("title").asText();
                JsonNode paragraphs = article.get("paragraphs");
                for (JsonNode para : paragraphs) {
                    if (MAX_DOCS_TO_PROCESS > 0 && docCounter >= MAX_DOCS_TO_PROCESS) {
                        LOGGER.info("Reached max docs limit: " + MAX_DOCS_TO_PROCESS);
                        processBatch(writeBuffer, batchWriter, checkpointManager, previousParseBatchCounter, parseBatchCounter);
                        return;
                    }

                    String context = para.get("context").asText();
                    if (context == null || context.isEmpty()) {
                        LOGGER.warning("Skipping empty context");
                        continue;
                    }
                    StringBuilder cleanTitle = TextUtils.lowerCaseText(new StringBuilder(title));
                    StringBuilder cleanContext = TextUtils.lowerCaseText(new StringBuilder(context));

                    ParsedDocument doc = new ParsedDocument(
                            String.valueOf(docCounter),
                            cleanTitle.toString(),
                            cleanContext.toString()
                    );
                    doc.addMetadata("source", "squad");
                    doc.addMetadata("article_title", cleanTitle.toString());
                    writeBuffer.add(doc);

                    if (writeBuffer.size() >= MAX_BATCH_SIZE) {
                        processBatch(writeBuffer, batchWriter, checkpointManager, previousParseBatchCounter, parseBatchCounter);
                        parseBatchCounter++;
                        writeBuffer.clear();
                    }
                    docCounter++;
                }
            }
        }

        processBatch(writeBuffer, batchWriter, checkpointManager, previousParseBatchCounter, parseBatchCounter);
    }

    /**
     * Unified logic to write batches. Handles both checkpointed and non-checkpointed runs.
     */
    private void processBatch(List<ParsedDocument> buffer,
                              BatchFileWriter batchWriter,
                              CheckpointManager checkpointManager,
                              int previousParseBatchCounter,
                              int currentParseBatchCounter) throws IOException {

        if (buffer.isEmpty()) return;
        boolean shouldWrite = !enableCheckpoint || (previousParseBatchCounter == -1 || currentParseBatchCounter > previousParseBatchCounter);

        if (shouldWrite) {
            batchWriter.writeBatch(new ArrayList<>(buffer), currentParseBatchCounter);
            if (enableCheckpoint && checkpointManager != null) {
                checkpointManager.writeCheckpointBatch(currentParseBatchCounter);
            }
        } else {
            LOGGER.fine("Skipping batch " + currentParseBatchCounter + " as it was already processed.");
        }
    }

    @Override
    public String getParserName() {
        return "SQuAD JSON Parser";
    }
}