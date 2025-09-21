package me.ryanhamshire.GriefPrevention;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class WordFinder
{
    private Pattern pattern;

    WordFinder(List<String> wordsToFind)
    {
        if (wordsToFind.size() == 0) return;

        StringBuilder patternBuilder = new StringBuilder();
        for (String word : wordsToFind)
        {
            if (!word.isEmpty() && !word.trim().isEmpty())
            {
                patternBuilder.append("|(([^\\w]|^)").append(Pattern.quote(word)).append("([^\\w]|$))");
            }
        }

        String patternString = patternBuilder.toString();
        if (patternString.length() > 1)
        {
            //trim extraneous leading pipe (|)
            patternString = patternString.substring(1);
        }
        // No words are defined, match nothing.
        else return;

        this.pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    boolean hasMatch(String input)
    {
        if (this.pattern == null) return false;

        Matcher matcher = this.pattern.matcher(input);
        return matcher.find();
    }
    
    /**
     * Censors all banned words in the input string by replacing each character with an asterisk (*)
     * @param input The input string to censor
     * @return The censored string with banned words replaced by asterisks
     */
    String censor(String input)
    {
        if (this.pattern == null || input == null || input.isEmpty()) return input;
        
        StringBuffer result = new StringBuffer();
        Matcher matcher = this.pattern.matcher(input);
        
        while (matcher.find()) {
            // Get the full match
            String match = matcher.group();
            if (match == null || match.trim().isEmpty()) continue;
            
            // Extract just the word part (without leading/trailing non-word chars)
            String word = match.replaceAll("^[^\\w]+|[^\\w]+$", "");
            if (word.isEmpty()) continue;
            
            // Create replacement with asterisks for the word, keeping the boundaries
            String replacement = match.replaceAll(
                "\\Q" + word + "\\E", 
                "*".repeat(word.length())
            );
            
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
}
