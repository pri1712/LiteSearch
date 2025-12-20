package com.pri1712.searchengine.parser;

public class ParserFactory {
    public static DocumentParser createParser(String filePath,int maxDocsToProcess,boolean enableCheckpoint,String outputDir) {
        String fileName = filePath.toLowerCase();

        if (fileName.contains("squad") && fileName.endsWith(".json")) {
            return new SquadParser(filePath,enableCheckpoint,outputDir);
        }

        throw new IllegalArgumentException("Unknown file format: " + fileName);
    }
}
