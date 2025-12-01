package org.rogmann.mcp.examples;

import java.util.List;

import org.rogmann.llmva4j.mcp.McpToolImplementation;
import org.rogmann.llmva4j.mcp.McpToolImplementations;

/**
 * Class to create a list of known tools.
 */
public class ToolListSupplier implements McpToolImplementations {

    @Override
    public List<McpToolImplementation> get() {
        return List.of(new CreateNewFileTool(), new ReadTextFileTool(),
                new ManageJavaMethodsTool(), new ManageJavaFieldsTool(),
                new FindFilesByGlobTool());
    }

}
