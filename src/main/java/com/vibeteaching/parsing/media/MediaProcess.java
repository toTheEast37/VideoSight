package com.vibeteaching.parsing.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 外部进程输出有界读取，stderr 只排空，不能混入识别文字或返回给页面。 */
final class MediaProcess {
    private MediaProcess() { }

    static byte[] run(List<String> command, Duration timeout, int maxOutputBytes, String operation) {
        return run(command, timeout, maxOutputBytes, operation, null, null);
    }

    static byte[] run(List<String> command, Duration timeout, int maxOutputBytes, String operation,
                      Path workingDirectory, Path standardInput) {
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(standardInput == null ? 2 : 3, task -> {
            Thread thread = new Thread(task, "parsing-process-output");
            thread.setDaemon(true);
            return thread;
        });
        try {
            if (standardInput != null && (!Files.isRegularFile(standardInput) || Files.size(standardInput) > 32L * 1024 * 1024)) {
                throw failure("PROCESS_INPUT_LIMIT", "媒体工具输入不存在或超过允许大小", false);
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) builder.directory(workingDirectory.toFile());
            process = builder.start();
            Process running = process;
            Future<byte[]> output = readers.submit(() -> readOutput(running, maxOutputBytes));
            Future<?> errors = readers.submit(() -> drain(running.getErrorStream()));
            Future<?> input = standardInput == null ? null : readers.submit(() -> writeInput(running, standardInput));
            if (standardInput == null) process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw failure(operation + "_TIMEOUT", operation + "执行超时", true);
            }
            byte[] result = output.get(5, TimeUnit.SECONDS);
            errors.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw failure(operation + "_FAILED", operation + "执行失败，请检查输入与本机工具", false);
            }
            if (input != null) input.get(5, TimeUnit.SECONDS);
            return result;
        } catch (RecognitionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(operation + "_INTERRUPTED", operation + "执行已中断", true);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RecognitionException recognition) throw recognition;
            throw failure(operation + "_OUTPUT_FAILED", operation + "输出读取失败", true);
        } catch (TimeoutException e) {
            throw failure(operation + "_OUTPUT_TIMEOUT", operation + "输出读取超时", true);
        } catch (IOException | RuntimeException e) {
            throw failure(operation + "_UNAVAILABLE", operation + "工具不可用或无法启动", false);
        } finally {
            if (process != null) {
                terminate(process);
                close(process.getInputStream());
                close(process.getErrorStream());
            }
            readers.shutdownNow();
        }
    }

    private static byte[] readOutput(Process process, int maxOutputBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream input = process.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxOutputBytes) {
                    terminate(process);
                    throw failure("PROCESS_OUTPUT_LIMIT", "媒体工具输出超过允许大小", false);
                }
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static void drain(InputStream input) {
        try (input) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) { /* 排空 stderr，避免进程阻塞。 */ }
        } catch (IOException ignored) { /* 主进程退出或被终止后流关闭。 */ }
    }

    private static void writeInput(Process process, Path source) {
        try (InputStream input = Files.newInputStream(source); OutputStream output = process.getOutputStream()) {
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > 32L * 1024 * 1024) {
                    terminate(process);
                    throw failure("PROCESS_INPUT_LIMIT", "媒体工具输入超过允许大小", false);
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw failure("PROCESS_INPUT_FAILED", "媒体工具输入读取失败", true);
        }
    }

    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            List<ProcessHandle> children = process.descendants().toList();
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            for (ProcessHandle child : children) {
                if (!child.isAlive() || System.nanoTime() >= deadline) continue;
                try { child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException | TimeoutException ignored) { }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void close(InputStream input) {
        try { input.close(); } catch (IOException ignored) { }
    }

    private static RecognitionException failure(String code, String message, boolean retryable) {
        return new RecognitionException(code, message, retryable, false);
    }
}
