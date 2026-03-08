package com.extractor.cli;

import java.nio.file.Path;

/**
 * Parsed command-line arguments for the OneNote PDF Extractor.
 */
public class CliArgs {
    private String sectionId;
    private String notebookName;
    private String sectionName;
    private Path outputDir;
    private boolean help;
    private int concurrency = 4;

    private static final Path DEFAULT_OUTPUT_DIR = Path.of("./onenote-export");

    public CliArgs() {
        this.outputDir = DEFAULT_OUTPUT_DIR;
    }

    /**
     * Validate argument combinations.
     *
     * <ul>
     *   <li>If sectionId is set, notebookName and sectionName are ignored.</li>
     *   <li>If sectionId is not set, both notebookName and sectionName must be provided.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the argument combination is invalid
     */
    public void validate() throws IllegalArgumentException {
        if (help) {
            return;
        }
        if (concurrency < 1 || concurrency > 20) {
            throw new IllegalArgumentException(
                "--concurrency must be between 1 and 20 (inclusive).");
        }
        if (isBlank(sectionId)) {
            if (isBlank(notebookName) || isBlank(sectionName)) {
                throw new IllegalArgumentException(
                    "Either --section-id or both --notebook and --section must be provided.");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public String getSectionId() { return sectionId; }
    public void setSectionId(String sectionId) { this.sectionId = sectionId; }

    public String getNotebookName() { return notebookName; }
    public void setNotebookName(String notebookName) { this.notebookName = notebookName; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public Path getOutputDir() { return outputDir; }
    public void setOutputDir(Path outputDir) { this.outputDir = outputDir; }

    public boolean isHelp() { return help; }
    public void setHelp(boolean help) { this.help = help; }

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
}
