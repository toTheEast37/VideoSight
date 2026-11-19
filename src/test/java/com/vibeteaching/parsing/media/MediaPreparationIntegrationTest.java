package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.ParseModels;
import com.vibeteaching.parsing.config.ParseProperties;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.storage.UploadObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 显式开启才使用真实 FFmpeg/Tesseract；不连接 MinIO、MQ 或付费 ASR。 */
@EnabledIfSystemProperty(named = "parse.media.integration", matches = "true")
class MediaPreparationIntegrationTest {
    @TempDir Path temp;

    @Test void fixedSamplesKeepTheActualStartAndIncludeTheShortFinalInterval() throws Exception {
        Path images = Files.createDirectory(temp.resolve("sequence"));
        for (int i = 0; i < 6; i++) {
            BufferedImage frame = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = frame.createGraphics();
            graphics.setColor(new Color(i * 30, 30, 30));
            graphics.fillRect(0, 0, 320, 240);
            graphics.dispose();
            ImageIO.write(frame, "png", images.resolve(String.format("image_%02d.png", i)).toFile());
        }
        Path video = temp.resolve("six-seconds.mkv");
        run(List.of(ffmpeg(), "-nostdin", "-v", "error", "-y", "-framerate", "1", "-i",
                images.resolve("image_%02d.png").toString(), "-c:v", "ffv1", video.toString()));
        MemoryStore store = new MemoryStore(video);
        MediaPreparation.PreparedMedia result = preparation(store, 360, Duration.ofMinutes(30)).prepare(store.asset(), "frames", "attempt");
        List<ParseModels.Input> frames = result.inputs().stream().filter(unit -> unit.kind() == ParseModels.Kind.OCR).toList();
        assertThat(result.hasAudio()).isFalse();
        assertThat(result.inputs()).noneMatch(unit -> unit.kind() == ParseModels.Kind.ASR);
        assertThat(result.durationMs()).isEqualTo(6000);
        assertThat(frames).hasSize(2);
        assertThat(frames.stream().map(ParseModels.Input::startMs)).containsExactly(0L, 5000L);
        for (ParseModels.Input frame : frames) assertThat(frame.endMs()).isEqualTo(frame.startMs());
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(store.bytes(frames.get(0).inputKey())));
        BufferedImage last = ImageIO.read(new ByteArrayInputStream(store.bytes(frames.get(1).inputKey())));
        assertThat(new Color(first.getRGB(100, 100)).getRed()).isCloseTo(0, within(3));
        assertThat(new Color(last.getRGB(100, 100)).getRed()).isCloseTo(150, within(3));
    }

    @Test void sixtyFiveSecondsOfSilenceIsKeptAndSplitWithGlobalOffsets() throws Exception {
        Path image = slide();
        Path video = temp.resolve("sixty-five-seconds.mp4");
        run(List.of(ffmpeg(), "-nostdin", "-v", "error", "-y", "-loop", "1", "-framerate", "1", "-i", image.toString(),
                "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "65", "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-c:a", "aac", video.toString()));
        MemoryStore store = new MemoryStore(video);
        MediaPreparation.PreparedMedia result = preparation(store, 360, Duration.ofMinutes(30)).prepare(store.asset(), "silent", "attempt");
        List<ParseModels.Input> audio = result.inputs().stream().filter(unit -> unit.kind() == ParseModels.Kind.ASR).toList();
        assertThat(result.hasAudio()).isTrue();
        assertThat(result.durationMs()).isEqualTo(65000);
        assertThat(audio).hasSize(2);
        assertThat(audio.get(0).startMs()).isZero();
        assertThat(audio.get(0).endMs()).isEqualTo(60000);
        assertThat(audio.get(1).startMs()).isEqualTo(60000);
        assertThat(audio.get(1).endMs()).isEqualTo(65000);
        byte[] first = pcm(store.bytes(audio.get(0).inputKey()));
        byte[] second = pcm(store.bytes(audio.get(1).inputKey()));
        assertThat(first).hasSize(60 * 32000);
        assertThat(second.length).isBetween(5 * 32000, 5 * 32000 + 3200);
        assertThat(first).containsOnly((byte) 0);
        assertThat(second).containsOnly((byte) 0);
    }

    @Test void delayedAudioRetainsItsPositionOnTheVideoTimeline() throws Exception {
        Path image = slide();
        Path video = temp.resolve("delayed-audio.mp4");
        run(List.of(ffmpeg(), "-nostdin", "-v", "error", "-y", "-loop", "1", "-framerate", "1", "-i", image.toString(),
                "-itsoffset", "5", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=10",
                "-t", "15", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", video.toString()));
        MemoryStore store = new MemoryStore(video);
        MediaPreparation.PreparedMedia result = preparation(store, 360, Duration.ofMinutes(30)).prepare(store.asset(), "delayed", "attempt");
        ParseModels.Input audio = result.inputs().stream().filter(unit -> unit.kind() == ParseModels.Kind.ASR).findFirst().orElseThrow();
        assertThat(audio.startMs()).isZero();
        assertThat(audio.endMs()).isEqualTo(15000);
        byte[] data = pcm(store.bytes(audio.inputKey()));
        assertThat(data.length).isBetween(15 * 32000 - 3200, 15 * 32000 + 3200);
        assertThat(Arrays.copyOfRange(data, 0, 4 * 32000)).containsOnly((byte) 0);
        boolean nonSilent = false;
        for (int i = 6 * 32000; i < 7 * 32000; i++) nonSilent |= data[i] != 0;
        assertThat(nonSilent).isTrue();
    }

    @Test void limitsRejectTheVideoInsteadOfSilentlyDroppingTheTail() throws Exception {
        Path video = temp.resolve("too-many-frames.mp4");
        run(List.of(ffmpeg(), "-nostdin", "-v", "error", "-y", "-loop", "1", "-framerate", "1", "-i", slide().toString(),
                "-t", "6", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", video.toString()));
        MemoryStore store = new MemoryStore(video);
        RecognitionException frames = assertThrows(RecognitionException.class,
                () -> preparation(store, 1, Duration.ofMinutes(30)).prepare(store.asset(), "limited", "attempt"));
        assertThat(frames.code()).isEqualTo("MEDIA_FRAME_LIMIT");
        RecognitionException duration = assertThrows(RecognitionException.class,
                () -> preparation(store, 360, Duration.ofSeconds(5)).prepare(store.asset(), "limited", "attempt"));
        assertThat(duration.code()).isEqualTo("MEDIA_DURATION_LIMIT");
        assertThat(store.objects).hasSize(1);
    }

    @Test void nonzeroOverallStartIsRejectedInsteadOfReportingTheWrongDuration() throws Exception {
        Path video = temp.resolve("nonzero-start.mkv");
        run(List.of(ffmpeg(), "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=red:s=320x240:r=1",
                "-t", "6", "-c:v", "ffv1", "-output_ts_offset", "7", video.toString()));
        MemoryStore store = new MemoryStore(video);
        RecognitionException error = assertThrows(RecognitionException.class,
                () -> preparation(store, 360, Duration.ofMinutes(30)).prepare(store.asset(), "offset", "attempt"));
        assertThat(error.code()).isEqualTo("MEDIA_TIMELINE_UNSUPPORTED");
        assertThat(store.objects).hasSize(1);
    }

    @Test void tesseractActuallyLoadsChineseAndEnglishModels() throws Exception {
        Path executable = Path.of(tesseract()).toAbsolutePath();
        String languages = new String(MediaProcess.run(List.of(executable.toString(), "--list-langs", "--tessdata-dir", "tessdata"),
                Duration.ofSeconds(15), 8192, "OCR_LANGS", executable.getParent(), null),
                StandardCharsets.UTF_8);
        assertThat(languages).contains("chi_sim", "eng");
        String result = new TesseractOcrClient(properties(360, Duration.ofMinutes(30))).recognize(slide());
        Files.writeString(temp.resolve("ocr-result.txt"), result, StandardCharsets.UTF_8);
        // 此处验证真实进程与中英模型加载；中文识别质量需保留样例人工核对，不能以进程成功代替。
        assertThat(result.replaceAll("\\s", "")).contains("Database123");
    }

    private MediaPreparation preparation(MemoryStore store, int maxFrames, Duration maxDuration) {
        return new MediaPreparation(store, properties(maxFrames, maxDuration), new ObjectMapper());
    }

    private ParseProperties properties(int maxFrames, Duration maxDuration) {
        return new ParseProperties(true, "media-integration", maxDuration, 268435456, 5, maxFrames, 60,
                temp.resolve("owned-work"), ffmpeg(), ffprobe(), tesseract(), "", "", "", Duration.ofSeconds(3),
                Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), 3, Duration.ofSeconds(10),
                "", "media-integration", "producer", "consumer");
    }

    private Path slide() throws IOException {
        Path image = temp.resolve("slide.png");
        BufferedImage frame = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1280, 720);
        graphics.setColor(Color.BLACK); graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 72));
        graphics.drawString("视频解析测试", 80, 150);
        graphics.setFont(new Font("Arial", Font.PLAIN, 72)); graphics.drawString("Database 123", 80, 300);
        graphics.dispose();
        ImageIO.write(frame, "png", image.toFile());
        return image;
    }

    private static void run(List<String> command) {
        MediaProcess.run(command, Duration.ofMinutes(2), 8192, "FIXTURE_FFMPEG");
    }

    private static String ffmpeg() { return System.getProperty("parse.media.ffmpeg", "ffmpeg"); }
    private static String ffprobe() { return System.getProperty("parse.media.ffprobe", "ffprobe"); }
    private static String tesseract() { return System.getProperty("parse.media.tesseract", "tesseract"); }

    private static byte[] pcm(byte[] wav) {
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 12; offset + 8 <= wav.length;) {
            long size = Integer.toUnsignedLong(data.getInt(offset + 4));
            if (new String(wav, offset, 4, StandardCharsets.US_ASCII).equals("data")) {
                return Arrays.copyOfRange(wav, offset + 8, offset + 8 + (int) size);
            }
            offset += 8 + (int) size + ((int) size & 1);
        }
        throw new AssertionError("Missing WAV data");
    }

    private static final class MemoryStore implements UploadObjectStore {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, String> hashes = new HashMap<>();
        private final VideoAsset asset;

        MemoryStore(Path source) throws Exception {
            byte[] bytes = Files.readAllBytes(source);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            objects.put("videos/source", bytes); hashes.put("videos/source", sha);
            asset = new VideoAsset(1, "upload", source.getFileName().toString(), bytes.length, sha,
                    bucket(), "videos/source", "video/mp4", Instant.now());
        }

        VideoAsset asset() { return asset; }
        byte[] bytes(String key) { return objects.get(key); }
        @Override public void ensureBucket() { }
        @Override public Optional<StoredObject> stat(String key) {
            return objects.containsKey(key) ? Optional.of(new StoredObject(objects.get(key).length, hashes.get(key))) : Optional.empty();
        }
        @Override public void put(String key, Path file, String contentType, String sha) {
            try { objects.put(key, Files.readAllBytes(file)); hashes.put(key, sha); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }
        @Override public InputStream open(String key, long offset, long length) {
            return new ByteArrayInputStream(objects.get(key), (int) offset, (int) length);
        }
        @Override public void delete(String key) { throw new AssertionError("Prepared inputs must remain stored"); }
        @Override public String bucket() { return "media-integration"; }
    }
}
