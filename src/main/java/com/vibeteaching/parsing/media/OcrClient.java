package com.vibeteaching.parsing.media;

import java.nio.file.Path;

public interface OcrClient {
    String recognize(Path image);
}
