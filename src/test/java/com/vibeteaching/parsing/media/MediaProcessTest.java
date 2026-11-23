package com.vibeteaching.parsing.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaProcessTest {
    @TempDir Path temp;
    @Test void stderrWarningsCannotBecomeRecognizedText() {
        byte[] result = MediaProcess.run(command("warning"), Duration.ofSeconds(10), 1024, "OCR");
        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("recognized text");
    }

    @Test void timeoutTerminatesProcessInsteadOfWaitingIndefinitely() {
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> MediaProcess.run(command("sleep"), Duration.ofMillis(250), 1024, "OCR"));
        assertThat(error.code()).isEqualTo("OCR_TIMEOUT");
        assertThat(error.uncertain()).isFalse();
    }

    @Test void largeStdoutIsBoundedAndCannotFillApplicationMemory() {
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> MediaProcess.run(command("large"), Duration.ofSeconds(10), 1024, "OCR"));
        assertThat(error.code()).isEqualTo("PROCESS_OUTPUT_LIMIT");
    }

    @Test void failedProcessNeverReturnsStderrAsAValidTranscript() {
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> MediaProcess.run(command("fail"), Duration.ofSeconds(10), 1024, "OCR"));
        assertThat(error.code()).isEqualTo("OCR_FAILED");
        assertThat(error.getMessage()).doesNotContain("private process error");
    }

    @Test void timeoutWaitsForExitBeforeCallerDeletesOwnedFiles() throws IOException {
        Path marker = temp.resolve("pid.txt");
        Path held = temp.resolve("held.txt");
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> MediaProcess.run(command("hold", marker.toString(), held.toString()), Duration.ofSeconds(2), 1024, "OCR"));
        assertThat(error.code()).isEqualTo("OCR_TIMEOUT");
        long pid = Long.parseLong(Files.readString(marker));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        Files.delete(held);
        assertThat(Files.exists(held)).isFalse();
    }

    private static List<String> command(String... arguments) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(executable, "-cp", classpath, ChildProcess.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    public static class ChildProcess {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "warning" -> { System.err.print("warning must not enter transcript"); System.out.print("recognized text"); }
                case "sleep" -> Thread.sleep(30000);
                case "large" -> System.out.print("x".repeat(1024 * 1024));
                case "fail" -> { System.err.print("private process error"); System.exit(2); }
                case "hold" -> {
                    try (var held = Files.newOutputStream(Path.of(args[2]))) {
                        held.write(1);
                        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                        Thread.sleep(30000);
                    }
                }
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
