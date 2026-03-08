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

    // --- Requirement 2.2: default concurrency level ---

    @Test
    void defaultConcurrencyIsFour() {
        var args = new CliArgs();

        assertThat(args.getConcurrency()).isEqualTo(4);
    }

    // --- Requirement 2.5: valid concurrency values ---

    @Test
    void acceptsConcurrencyOf1() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(1);

        assertThatNoException().isThrownBy(args::validate);
        assertThat(args.getConcurrency()).isEqualTo(1);
    }

    @Test
    void acceptsConcurrencyOf20() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(20);

        assertThatNoException().isThrownBy(args::validate);
        assertThat(args.getConcurrency()).isEqualTo(20);
    }

    @Test
    void acceptsConcurrencyOf10() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(10);

        assertThatNoException().isThrownBy(args::validate);
        assertThat(args.getConcurrency()).isEqualTo(10);
    }

    // --- Requirement 2.3: reject concurrency < 1 ---

    @Test
    void rejectsConcurrencyOfZero() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(0);

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--concurrency");
    }

    @Test
    void rejectsConcurrencyOfNegativeValue() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(-5);

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--concurrency");
    }

    // --- Requirement 2.4: reject concurrency > 20 ---

    @Test
    void rejectsConcurrencyOf21() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(21);

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--concurrency");
    }

    @Test
    void rejectsConcurrencyOf100() {
        var args = new CliArgs();
        args.setSectionId("abc-123");
        args.setConcurrency(100);

        assertThatThrownBy(args::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--concurrency");
    }

    // --- Requirement 1.6 + 2.3/2.4: help flag bypasses concurrency validation ---

    @Test
    void helpFlagBypassesConcurrencyValidation() {
        var args = new CliArgs();
        args.setHelp(true);
        args.setConcurrency(0);

        assertThatNoException().isThrownBy(args::validate);
    }
}
