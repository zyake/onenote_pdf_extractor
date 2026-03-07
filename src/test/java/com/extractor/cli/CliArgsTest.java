package com.extractor.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for CliArgs validation and defaults.
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6
 */
class CliArgsTest {

    // --- Requirement 1.1: section-id-only input ---

    @Test
    void validatesWhenSectionIdProvided() {
        var args = new CliArgs();
        args.setSectionId("abc-123");

        assertThatNoException().isThrownBy(args::validate);
    }

    @Test
    void sectionIdIgnoresNotebookAndSection() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setNotebookName("My Notebook");
        args.setSectionName("My Section");

        assertThatNoException().isThrownBy(args::validate);
        assertThat(args.getSectionId()).isEqualTo("abc-123");
    }

    // --- Requirement 1.2: notebook + section input ---

    @Test
    void validatesWhenNotebookAndSectionProvided() {
        var args = new CliArgs();
        args.setNotebookName("My Notebook");
        args.setSectionName("My Section");

        assertThatNoException().isThrownBy(args::validate);
    }

    // --- Requirement 1.3: invalid combinations ---

    @Test
    void rejectsWhenNoArgumentsProvided() {
        var args = new CliArgs();

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--section-id")
                .hasMessageContaining("--notebook")
                .hasMessageContaining("--section");
    }

    @Test
    void rejectsWhenOnlyNotebookProvided() {
        var args = new CliArgs();
        args.setNotebookName("My Notebook");

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenOnlySectionProvided() {
        var args = new CliArgs();
        args.setSectionName("My Section");

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenSectionIdIsBlank() {
        var args = new CliArgs();
        args.setSectionId("   ");

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenNotebookIsBlankAndSectionSet() {
        var args = new CliArgs();
        args.setNotebookName("");
        args.setSectionName("My Section");

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenSectionIsBlankAndNotebookSet() {
        var args = new CliArgs();
        args.setNotebookName("My Notebook");
        args.setSectionName("  ");

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Requirement 1.5: default output directory ---

    @Test
    void defaultOutputDirIsOnenoteExport() {
        var args = new CliArgs();

        assertThat(args.getOutputDir()).isEqualTo(Path.of("./onenote-export"));
    }

    // --- Requirement 1.4: custom output directory ---

    @Test
    void customOutputDirIsUsed() {
        var args = new CliArgs();
        var customDir = Path.of("/tmp/my-export");
        args.setOutputDir(customDir);

        assertThat(args.getOutputDir()).isEqualTo(customDir);
    }

    // --- Requirement 1.6: help flag bypasses validation ---

    @Test
    void helpFlagBypassesValidationWithNoOtherArgs() {
        var args = new CliArgs();
        args.setHelp(true);

        assertThatNoException().isThrownBy(args::validate);
    }

    @Test
    void helpFlagBypassesValidationEvenWithInvalidCombination() {
        var args = new CliArgs();
        args.setHelp(true);
        args.setNotebookName("Only notebook, no section");

        assertThatNoException().isThrownBy(args::validate);
    }
}
