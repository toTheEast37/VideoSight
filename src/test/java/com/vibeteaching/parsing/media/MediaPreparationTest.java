package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.upload.storage.UploadObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaPreparationTest {
    @TempDir Path temp;

    @Test void verifiesDownloadedInputAndRemovesOnlyItsTemporaryDirectory() throws IOException {
        byte[] bytes = "saved-input".getBytes(StandardCharsets.UTF_8);
        ObjectStore storage = new ObjectStore(bytes, bytes.length, sha(bytes));
        Path sibling = Files.writeString(temp.resolve("keep.txt"), "do not delete");
        AtomicBoolean existedDuringAction = new AtomicBoolean();
        String result = preparation(storage).withInput("parsing/task/attempt/audio/audio_00000.wav", path -> {
            existedDuringAction.set(Files.isRegularFile(path));
            try { return Files.readString(path); }
            catch (IOException e) { throw new AssertionError(e); }
        });
        assertThat(result).isEqualTo("saved-input");
        assertThat(existedDuringAction).isTrue();
        assertThat(storage.deleted).isFalse();
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(sibling); }
    }

    @Test void truncatedObjectNeverReachesPaidRecognitionAndRemainsRetryable() throws IOException {
        ObjectStore storage = new ObjectStore(new byte[]{1, 2}, 5, null);
        AtomicBoolean invoked = new AtomicBoolean();
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> preparation(storage).withInput("parsing/task/attempt/audio/audio_00000.wav", path -> invoked.getAndSet(true)));
        assertThat(error.code()).isEqualTo("MEDIA_INPUT_TRUNCATED");
        assertThat(error.retryable()).isTrue();
        assertThat(error.uncertain()).isFalse();
        assertThat(invoked).isFalse();
        assertThat(storage.deleted).isFalse();
        try (var files = Files.list(temp)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void detectsCorruptionEvenWhenStoredLengthIsCorrect() {
        byte[] bytes = "altered".getBytes(StandardCharsets.UTF_8);
        ObjectStore storage = new ObjectStore(bytes, bytes.length, sha("original".getBytes(StandardCharsets.UTF_8)));
        AtomicBoolean invoked = new AtomicBoolean();
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> preparation(storage).withInput("parsing/task/attempt/frames/frame_00001.jpg", path -> invoked.getAndSet(true)));
        assertThat(error.code()).isEqualTo("MEDIA_INPUT_DIGEST_MISMATCH");
        assertThat(error.uncertain()).isFalse();
        assertThat(invoked).isFalse();
    }

    @Test void rejectsOversizedStoredUnitBeforeOpeningItsStream() {
        ObjectStore storage = new ObjectStore(new byte[0], 32L * 1024 * 1024 + 1, null);
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> preparation(storage).withInput("parsing/task/attempt/audio/audio_00000.wav", path -> "unused"));
        assertThat(error.code()).isEqualTo("MEDIA_INPUT_SIZE_MISMATCH");
        assertThat(storage.opened).isFalse();
    }

    @Test void preservesUncertainRecognitionErrorWhileCleaningLocalInput() throws IOException {
        byte[] bytes = new byte[]{1, 2};
        ObjectStore storage = new ObjectStore(bytes, bytes.length, sha(bytes));
        RecognitionException original = new RecognitionException("ASR_UNCERTAIN", "结果待确认", false, true);
        RecognitionException result = assertThrows(RecognitionException.class,
                () -> preparation(storage).withInput("parsing/task/attempt/audio/audio_00000.wav", path -> { throw original; }));
        assertThat(result).isSameAs(original);
        try (var files = Files.list(temp)) { assertThat(files.toList()).isEmpty(); }
    }

    private MediaPreparation preparation(ObjectStore storage) {
        return new MediaPreparation(storage,
                MediaTestProperties.properties(temp, "", "", Duration.ofSeconds(3)), new ObjectMapper());
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static class ObjectStore implements UploadObjectStore {
        private final byte[] bytes;
        private final long size;
        private final String sha;
        private boolean opened;
        private boolean deleted;
        ObjectStore(byte[] bytes, long size, String sha) { this.bytes = bytes; this.size = size; this.sha = sha; }
        @Override public void ensureBucket() { }
        @Override public Optional<StoredObject> stat(String key) { return Optional.of(new StoredObject(size, sha)); }
        @Override public void put(String key, Path file, String contentType, String sha256) { throw new UnsupportedOperationException(); }
        @Override public InputStream open(String key, long offset, long length) { opened = true; return new ByteArrayInputStream(bytes); }
        @Override public void delete(String key) { deleted = true; }
        @Override public String bucket() { return "test-videos"; }
    }
}
