package com.axes.pmweather_aeronautics;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;

/** Counts compressed bytes; closing the writer also completes the gzip footer. */
final class CompressedDebugOutput {
    private final Counter counter;
    final BufferedWriter writer;

    CompressedDebugOutput(Path path) throws IOException {
        counter = new Counter(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW));
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(counter, 32768, true), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            counter.close();
            throw e;
        }
    }

    long bytes() { return counter.count; }

    private static final class Counter extends FilterOutputStream {
        long count;
        Counter(OutputStream out) { super(out); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length); count += length;
        }
    }
}
