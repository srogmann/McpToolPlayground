package org.rogmann.mcp.examples;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.rogmann.llmva4j.LightweightJsonHandler;
import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolInputSchema;
import org.rogmann.llmva4j.mcp.McpToolInterface;
import org.rogmann.llmva4j.mcp.McpToolPropertyDescription;

/**
 * Explains a list of unknown words if they are contained in a glossary.
 */
public class GlossaryTool implements McpToolImplementation {
    /** logger */
    private static final Logger LOG = Logger.getLogger(GlossaryTool.class.getName());

    /** Property name of a path to a markdown file */
    private static final String PROP_PATH = "mcp.glossary.path";

    /** Property name of a description of the glossary */
    private static final String PROP_DESCRIPTION = "mcp.glossary.description";

    private final String toolDescription;

    /** Map from term (lower case) to description of the term */
    private Map<String, GlossaryEntry> mapGlossary = new LinkedHashMap<>();

    /** Map from key to key of referenced entry */
    private Map<String, String> mapReferences = new HashMap<>();

    /**
     * Entry of the glossary.
     * @param term name of the term
     * @param description descriptio of the term
     */
    record GlossaryEntry(String term, String description) { }

    /**
     * Initializes the GlossaryTool by reading a UTF-8 encoded markdown file specified by the PROP_PATH property.
     * The file should contain sections in the format:
     * 
     * <pre>
     * # &lt;TERM-NAME&gt;
     * &lt;TERM-DESCRIPTION&gt;
     * }
     * </pre>
     * <p>Each section's term name is used as the key, and the multi-line description is stored as the value in mapGlossary.</p>
     */
    public GlossaryTool() {
        String path = System.getProperty(PROP_PATH);
        if (path == null || path.isBlank()) {
            throw new RuntimeException("Property " + PROP_PATH + " is not set");
        }

        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("Glossary file not found: " + path);
        }
        
        toolDescription = System.getProperty(PROP_DESCRIPTION, "Tool to explain technical words or concepts.");
        LOG.info("Tool-Description: " + toolDescription);

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String currentTerm = null;
            StringBuilder sbDescription = new StringBuilder();

            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("# ")) {
                    // Save previous term and description if exists
                    String description = sbDescription.toString().trim();
                    addEntry(currentTerm, description);
                    // Start new term
                    currentTerm = line.substring(2).trim();
                    sbDescription.setLength(0);
                } else if (currentTerm != null) {
                    // Append to current description
                    if (sbDescription.length() > 0) {
                        sbDescription.append("\n");
                    }
                    sbDescription.append(line);
                }
                // Ignore lines not part of any term description.
            }

            // Don't forget the last term.
            String description = sbDescription.toString().trim();
            addEntry(currentTerm, description);
            LOG.info("#entries: " + mapGlossary.size());
        } catch (IOException e) {
            throw new RuntimeException("Error reading glossary file: " + path, e);
        }
    }

    private void addEntry(String term, String description) {
        if (term != null && !description.isEmpty()) {
            String key = convertToKey(term);
            
            if (description.startsWith("-> ")) {
                String keyRef = convertToKey(description.substring(3));
                mapReferences.put(key, keyRef);
                return;
            }

            GlossaryEntry existingEntry = mapGlossary.get(key);
            if (existingEntry != null) {
                LOG.warning(String.format("Duplicate key (%s) of terms (%s) and (%s) in glossary-file.",
                        key, existingEntry.term(), term));
            } else {
                mapGlossary.put(key, new GlossaryEntry(term, description));
            }
        }
    }

    private static String convertToKey(String term) {
        return term.toLowerCase().replaceAll("[ _-]", "");
    }

    @Override
    public String getName() {
        return "glossary-tool";
    }

    @Override
    public McpToolInterface getTool() {
        Map<String, McpToolPropertyDescription> mapProps = new LinkedHashMap<>();
        McpToolPropertyDescription descKeyword = new McpToolPropertyDescription("string", "list of words or concepts to be explained.");
        mapProps.put("words", descKeyword);
        List<String> aRequired = List.of("words");
        McpToolInputSchema inputSchema = new McpToolInputSchema("object", mapProps, aRequired);
        McpToolInterface mcpTool = new McpToolInterface("glossary-tool", "Glossary Tool", toolDescription, inputSchema);
        return mcpTool;

    }

    @Override
    public List<Map<String, Object>> call(Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mapArgs = LightweightJsonHandler.getJsonValue(params, "arguments", Map.class);
        if (mapArgs == null) {
            throw new RuntimeException("Missing arguments in tool-call");
        }

        Set<String> setKeysProcessed = new HashSet<>();
        List<String> listTermsFound = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        Object oWords = mapArgs.get("words");
        List<String> listWords = new ArrayList<>();
        if (oWords == null) {
            throw new RuntimeException("words-entry is missing in request");
        }
        if (oWords instanceof String[] aWords) {
            Arrays.stream(aWords).forEach(listWords::add);
        } else {
            final String[] aWords = oWords.toString().split(" *, *"); 
            Arrays.stream(aWords).forEach(listWords::add);
        }
        LOG.info("Words: " + listWords);
        for (String word : listWords) {
            String key = convertToKey(word);
            processKey(key, setKeysProcessed, sb, listTermsFound);
        }

        LOG.info("Keys checked: " + setKeysProcessed);
        String textResponse;
        if (sb.isEmpty()) {
            textResponse = "Unfortunately none of the words is known to the glossary tool";
        } else {
            textResponse = sb.toString();
            LOG.info("Terms found: " + listTermsFound);
        }

        List<Map<String, Object>> listResults = new ArrayList<>();
        Map<String, Object> mapDetails = new LinkedHashMap<>();
        mapDetails.put("type",  "text");
        mapDetails.put("text", textResponse);
        listResults.add(mapDetails);
        return listResults;
    }

    private void processKey(String key, Set<String> setKeysProcessed, StringBuilder sb, List<String> listTermsFound) {
        String keyRef = mapReferences.get(key);
        String keyLookup = (keyRef == null) ? key : keyRef;

        if (setKeysProcessed.add(keyLookup)) {
            GlossaryEntry entry = mapGlossary.get(keyLookup);
            if (entry != null) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("# ").append(entry.term()).append('\n');
                sb.append(entry.description());
                listTermsFound.add(entry.term());
            }
        }
    }

}
