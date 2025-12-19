package com.pri1712.searchengine.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pri1712.searchengine.model.ParsedDocument;
import com.pri1712.searchengine.utils.BatchFileWriter;
import com.pri1712.searchengine.utils.TextUtils;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/***
 * Class for parsing squad json format.
 */
public class SquadParser implements DocumentParser {

    private String dataFilePath;
    private final String outputDir;
    private final String parserBatchCheckpointFile = "parserCheckpoint.txt";
    private final CheckpointManager checkpointManager = new CheckpointManager(parserBatchCheckpointFile);
    private int MAX_DOCS_TO_PROCESS;
    ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
            .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    private boolean enableCheckpoint;

    public SquadParser(String dataFilePath,int MAX_DOCS_TO_PROCESS,boolean enableCheckpoint,String outputDir) {
        this.dataFilePath = dataFilePath;
        this.MAX_DOCS_TO_PROCESS = MAX_DOCS_TO_PROCESS;
        this.enableCheckpoint = enableCheckpoint;
        this.outputDir  = outputDir;
    }

    @Override
    public List<ParsedDocument> parse() throws IOException {
        List<ParsedDocument> writeBuffer = new ArrayList<>();
        List<ParsedDocument> allDocuments = new ArrayList<>();
        if (dataFilePath == null || dataFilePath.isEmpty()) {
            throw new FileNotFoundException();
        }
        BatchFileWriter batchWriter = null;
        CheckpointManager checkpointManager = null;
        int parseBatchCounter = 0;
        int previousParseBatchCounter = -1;
        if (enableCheckpoint) {
            batchWriter = new BatchFileWriter(outputDir);
            checkpointManager = new CheckpointManager("squadParserCheckpoint.txt");
            previousParseBatchCounter = checkpointManager.readCheckpointBatch();
        }
        try (Reader reader = new FileReader(dataFilePath)) {
            JsonNode root = mapper.readTree(reader);
            JsonNode data = root.get("data");

            int docId = 0;
            for (JsonNode article : data) {
                String title = article.get("title").asText();
                JsonNode paragraphs = article.get("paragraphs");

                for (JsonNode para : paragraphs) {
                    String context = para.get("context").asText();

                    StringBuilder cleanTitle = TextUtils.lowerCaseText(new StringBuilder(title));
                    StringBuilder cleanContext = TextUtils.lowerCaseText(new StringBuilder(context));
                    if (cleanContext.isEmpty()) {
                        continue;
                    }

                    ParsedDocument doc = new ParsedDocument(
                            "squad_" + docId++,
                            cleanTitle.toString(),
                            cleanContext.toString()
                    );
                    doc.addMetadata("source", "squad");
                    doc.addMetadata("article_title", cleanTitle.toString());

                    if (enableCheckpoint) {
                        writeBuffer.add(doc);
                        if (writeBuffer.size() >= MAX_DOCS_TO_PROCESS) {
                            List<ParsedDocument> batchToWrite = new ArrayList<>(writeBuffer);
                            writeBuffer.clear();

                            if (previousParseBatchCounter == -1 || parseBatchCounter > previousParseBatchCounter) {
                                assert batchWriter != null;
                                batchWriter.writeBatch(batchToWrite, parseBatchCounter);
                            }
                            checkpointManager.writeCheckpointBatch(parseBatchCounter);
                            parseBatchCounter++;
                        }
                    } else {
                        allDocuments.add(doc);
                    }

                    if (MAX_DOCS_TO_PROCESS >= 0 && docId >= MAX_DOCS_TO_PROCESS) {
                        if (enableCheckpoint && !writeBuffer.isEmpty()) {
                            if (previousParseBatchCounter == -1 || parseBatchCounter > previousParseBatchCounter) {
                                assert batchWriter != null;
                                batchWriter.writeBatch(writeBuffer, parseBatchCounter);
                            }
                            checkpointManager.writeCheckpointBatch(parseBatchCounter);
                        }
                        return allDocuments;
                    }
                }
            }
        }
        if (enableCheckpoint && !writeBuffer.isEmpty()) {
            if (previousParseBatchCounter == -1 || parseBatchCounter > previousParseBatchCounter) {
                assert batchWriter != null;
                batchWriter.writeBatch(writeBuffer, parseBatchCounter);
            }
            checkpointManager.writeCheckpointBatch(parseBatchCounter);
        }
        return allDocuments;
    }

    @Override
    public String getParserName() {
        return "SQuAD JSON Parser";
    }
}
