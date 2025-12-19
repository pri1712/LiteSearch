package com.pri1712.searchengine.utils;

import com.pri1712.searchengine.model.TokenizedChunk;
import com.pri1712.searchengine.model.TokenizedData;
import com.pri1712.searchengine.model.data.Chunk;
import com.pri1712.searchengine.utils.WikiDocument;
import org.tartarus.snowball.ext.PorterStemmer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class TextUtils {
    private static final Logger LOGGER = Logger.getLogger(TextUtils.class.getName());

    private TextUtils() {}

    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "the", "of", "at", "on", "upon", "in", "to", "from", "out",
            "as", "so", "such", "or", "and", "those", "this", "these", "that",
            "for", "is", "was", "am", "are", "'s", "been", "were"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile("\\p{L}+");

    public static String normalizeString(String input) {
        if (input == null || input.isEmpty()) return "";
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return "";
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");             // remove diacritic marks
        normalized = normalized.toLowerCase()
                .replaceAll("[^a-z0-9]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return normalized;
    }
    public static StringBuilder lowerCaseText(StringBuilder text) {
        if (text == null || text.toString().isEmpty()) {
            return new StringBuilder();
        }

        String raw = text.toString();
        if (raw.matches("(?is)^#redirect\\s*\\[\\[.*?\\]\\]")) {
            return new StringBuilder();
        }

        return new StringBuilder(normalizeString(raw));
    }

    public static WikiDocument normalizeDocument(WikiDocument wikiDocument) {
        if (wikiDocument == null) return null;
        WikiDocument normalized = new WikiDocument();
        normalized.setId(wikiDocument.getId());
        normalized.setTimestamp(wikiDocument.getTimestamp());

        normalized.setTitle(normalizeString(wikiDocument.getTitle()));
        normalized.setText(normalizeString(wikiDocument.getText()));
        return normalized;
    }

    public static TokenizedData tokenizeDocument(WikiDocument wikiDocument) {
        if (wikiDocument == null) return null;

        String originalText = wikiDocument.getText() == null ? "" : wikiDocument.getText();
        String originalTitle = wikiDocument.getTitle() == null ? "" : wikiDocument.getTitle();

        List<String> textTokens = new ArrayList<>();
        List<String> titleTokens = new ArrayList<>();

        PorterStemmer stemmer = new PorterStemmer();

        Matcher textMatcher = WORD_PATTERN.matcher(originalText);
        while (textMatcher.find()) {
            String token = textMatcher.group();
            if (token.length() <= 1) continue;
            String lower = token.toLowerCase();
            if (ENGLISH_STOP_WORDS.contains(lower)) continue;
            stemmer.setCurrent(lower);
            stemmer.stem();
            String stemmed = stemmer.getCurrent();
            LOGGER.fine(() -> "adding token to textTokens: " + stemmed);
            textTokens.add(stemmed);
        }

        Matcher titleMatcher = WORD_PATTERN.matcher(originalTitle);
        while (titleMatcher.find()) {
            String token = titleMatcher.group();
            if (token.length() <= 1) continue;
            String lower = token.toLowerCase();
            stemmer.setCurrent(lower);
            stemmer.stem();
            String stemmed = stemmer.getCurrent();
            LOGGER.fine(() -> "adding token to titleTokens: " + stemmed);
            titleTokens.add(stemmed);
        }

        return new TokenizedData(textTokens, titleTokens, wikiDocument.getId());
    }

    public static TokenizedChunk tokenizeChunk(Chunk chunk) {
        Matcher textMatcher = WORD_PATTERN.matcher(chunk.getChunkText());
        PorterStemmer stemmer = new PorterStemmer();
        List<String> textTokens = new ArrayList<>();

        while (textMatcher.find()) {
            String token = textMatcher.group();
            if (token.length() <= 1) continue;
            String lower = token.toLowerCase();
            if (ENGLISH_STOP_WORDS.contains(lower)) continue;
            stemmer.setCurrent(lower);
            stemmer.stem();
            String stemmed = stemmer.getCurrent();
            LOGGER.fine(() -> "adding token to textTokens: " + stemmed);
            textTokens.add(stemmed);
        }
        LOGGER.fine("text tokens: " + textTokens);
        return new TokenizedChunk(textTokens,chunk.getChunkId().toString());
    }

    public static List<String> tokenizeQuery(String queryText) {
        if (queryText == null || queryText.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = new ArrayList<>();
        PorterStemmer stemmer = new PorterStemmer();

        Matcher matcher = WORD_PATTERN.matcher(queryText);

        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() <= 1) continue;

            String lower = token.toLowerCase();
            if (ENGLISH_STOP_WORDS.contains(lower)) continue;

            stemmer.setCurrent(lower);
            stemmer.stem();
            String stemmed = stemmer.getCurrent();

            LOGGER.fine(() -> "adding token to queryTokens: " + stemmed);
            queryTokens.add(stemmed);
        }

        LOGGER.fine("query tokens: " + queryTokens);
        return queryTokens;
    }
}