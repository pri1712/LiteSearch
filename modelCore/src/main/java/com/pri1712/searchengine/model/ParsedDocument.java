package com.pri1712.searchengine.model;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.*;

public class ParsedDocument {
    private static final Logger LOGGER = Logger.getLogger(ParsedDocument.class.getName());

    private String id;
    private String title;
    private String text;
    private String timestamp;
    private Map<String,String> metadata;
    public ParsedDocument() {}
    public ParsedDocument(String id, String title, String text, String timestamp) {
        this.id = id;
        this.title = title;
        this.text = text;
        this.timestamp = timestamp;
//        LOGGER.info(String.format(
//                "Parsed Page -> ID: %s | Title: %s | Text length: %d | Timestamp: %s",
//                id,
//                title,
//                text.length(),
//                timestamp
//        ));
        this.metadata = new HashMap<>();
    }

    public ParsedDocument(String id, String title, String text) {
        this.id = id;
        this.title = title;
        this.text = text;
        this.timestamp = null;
        this.metadata = new HashMap<>();
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
    public void addMetadata(String key, String value) {
        this.metadata.put(key,value);
    }
}
