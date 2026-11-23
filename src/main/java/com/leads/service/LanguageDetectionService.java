package com.leads.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Free, local, no-AI-call heuristic used only to decide whether the chat
 * UI's translate icon should appear on a message — the actual (paid)
 * translation only happens later, on demand, when an agent clicks that icon.
 * Deliberately not a full NLP library: a non-Latin script is detected by
 * Unicode block, and Latin-script text is scored against a small set of
 * common stopwords per language. Biased toward "en" (i.e. hide the icon)
 * when the signal is weak or the text is too short to be reliable — a false
 * negative just means no icon on a message that could have used one, while a
 * false positive puts a misleading translate icon on genuine English text,
 * which is the more visible/annoying failure mode of the two.
 */
@Service
public class LanguageDetectionService {

    private static final int MIN_CHARS_FOR_LATIN_HEURISTIC = 12;

    // A short, common-word signal per language — not a dictionary. Only
    // languages worth flagging in a customer-chat context are included.
    private static final Map<String, String[]> LATIN_STOPWORDS = new LinkedHashMap<>();
    static {
        LATIN_STOPWORDS.put("fr", new String[]{" le ", " la ", " les ", " et ", " je ", " vous ", " est ", " pour ", " bonjour ", " merci ", " oui ", " non "});
        LATIN_STOPWORDS.put("es", new String[]{" el ", " la ", " los ", " y ", " yo ", " usted ", " es ", " para ", " hola ", " gracias ", " si ", " si, ", " no "});
        LATIN_STOPWORDS.put("pt", new String[]{" o ", " a ", " os ", " e ", " eu ", " voce ", " para ", " obrigado ", " obrigada ", " sim ", " nao "});
        LATIN_STOPWORDS.put("de", new String[]{" der ", " die ", " das ", " und ", " ich ", " sie ", " ist ", " fur ", " danke ", " bitte ", " ja ", " nein "});
        LATIN_STOPWORDS.put("it", new String[]{" il ", " la ", " di ", " e ", " io ", " lei ", " per ", " grazie ", " si ", " no ", " ciao "});
        LATIN_STOPWORDS.put("id", new String[]{" yang ", " dan ", " saya ", " anda ", " untuk ", " terima kasih ", " ya ", " tidak ", " apa "});
        LATIN_STOPWORDS.put("tl", new String[]{" ang ", " ng ", " ako ", " kayo ", " salamat ", " po ", " oo ", " hindi ", " paano "});
    }

    /** ISO 639-1 code, or null if the text doesn't provide enough signal to detect anything (treated the same as English by callers). */
    public String detect(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();

        String scriptLanguage = detectByScript(trimmed);
        if (scriptLanguage != null) return scriptLanguage;

        if (trimmed.length() < MIN_CHARS_FOR_LATIN_HEURISTIC) return null;
        return detectLatinByStopwords(trimmed);
    }

    private String detectByScript(String text) {
        int arabic = 0, cyrillic = 0, devanagari = 0, cjk = 0, hebrew = 0, thai = 0, total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || !Character.isLetter(c)) continue;
            total++;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.ARABIC || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT) arabic++;
            else if (block == Character.UnicodeBlock.CYRILLIC) cyrillic++;
            else if (block == Character.UnicodeBlock.DEVANAGARI) devanagari++;
            else if (block == Character.UnicodeBlock.HEBREW) hebrew++;
            else if (block == Character.UnicodeBlock.THAI) thai++;
            else if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES) cjk++;
        }
        if (total == 0) return null;
        // A small fraction of non-Latin letters can appear in otherwise-English
        // text (emoji-adjacent symbols, names); require a clear majority.
        if (arabic > total * 0.4) return "ar";
        if (cyrillic > total * 0.4) return "ru";
        if (devanagari > total * 0.4) return "hi";
        if (hebrew > total * 0.4) return "he";
        if (thai > total * 0.4) return "th";
        if (cjk > total * 0.4) return "zh";
        return null;
    }

    private String detectLatinByStopwords(String text) {
        String padded = " " + text.toLowerCase() + " ";
        String bestLang = null;
        int bestScore = 0;
        for (var entry : LATIN_STOPWORDS.entrySet()) {
            int score = 0;
            for (String word : entry.getValue()) {
                if (padded.contains(word)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestLang = entry.getKey();
            }
        }
        // Require at least 2 distinct stopword hits before overriding the
        // English default — a single incidental match (e.g. "no") is too weak.
        return bestScore >= 2 ? bestLang : null;
    }
}
