package com.pri1712.searchengine.parser;

import com.pri1712.searchengine.model.ParsedDocument;

import java.io.IOException;
import java.util.List;

public interface DocumentParser {
    void parse() throws IOException;
    String getParserName();
}
