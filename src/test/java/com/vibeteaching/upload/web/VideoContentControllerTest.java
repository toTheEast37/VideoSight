package com.vibeteaching.upload.web;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.UploadService;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.storage.UploadObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 控制器使用小字节对象替身验证 HTTP 读取契约，不验证视频编解码。 */
class VideoContentControllerTest {
    private final UploadService uploads = mock(UploadService.class);
    private final UploadObjectStore objects = mock(UploadObjectStore.class);
    private final String key = "videos/upload-1/original";
    private final String sha = "a".repeat(64);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(uploads.asset(42)).thenReturn(new VideoAsset(42, "upload-1", "测试视频.mp4", 6,
                sha, "videos", key, "video/mp4", Instant.parse("2026-11-15T02:00:00Z")));
        when(objects.bucket()).thenReturn("videos");
        when(objects.stat(key)).thenReturn(Optional.of(new UploadObjectStore.StoredObject(6, sha)));
        mvc = MockMvcBuilders.standaloneSetup(new VideoContentController(uploads, objects))
                .setControllerAdvice(new UploadErrorHandler()).build();
    }

    @Test
    void singleRangeReturnsOnlyRequestedBytesWithCorrectPartialContentHeaders() throws Exception {
        when(objects.open(key, 2, 3)).thenReturn(new ByteArrayInputStream(new byte[]{3, 4, 5}));

        MvcResult pending = mvc.perform(get("/api/videos/42/content").header(HttpHeaders.RANGE, "bytes=2-4"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-4/6"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "3"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().bytes(new byte[]{3, 4, 5}));
        verify(objects).open(key, 2, 3);
    }

    @Test
    void suffixRangeReadsTheLastRequestedBytes() throws Exception {
        when(objects.open(key, 4, 2)).thenReturn(new ByteArrayInputStream(new byte[]{5, 6}));
        MvcResult pending = mvc.perform(get("/api/videos/42/content").header(HttpHeaders.RANGE, "bytes=-2"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 4-5/6"))
                .andExpect(content().bytes(new byte[]{5, 6}));
    }

    @Test
    void fullContentWithoutRangeReturnsTheWholeObject() throws Exception {
        when(objects.open(key, 0, 6)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6}));
        MvcResult pending = mvc.perform(get("/api/videos/42/content")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "6"))
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4, 5, 6}));
    }

    @Test
    void unsatisfiableRangeIsRejectedBeforeOpeningTheObject() throws Exception {
        mvc.perform(get("/api/videos/42/content").header(HttpHeaders.RANGE, "bytes=6-8"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */6"));
        verify(objects, never()).open(anyString(), anyLong(), anyLong());
    }

    @Test
    void missingAssetReturnsNotFoundWithoutAccessingStorage() throws Exception {
        when(uploads.asset(404)).thenThrow(new UploadException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "视频不存在"));
        mvc.perform(get("/api/videos/404/content")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        verify(objects, never()).stat(anyString());
    }

    @Test
    void missingObjectDoesNotPretendThatTheSavedVideoCanBeRead() throws Exception {
        when(objects.stat(key)).thenReturn(Optional.empty());
        mvc.perform(get("/api/videos/42/content")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_OBJECT_MISSING"));
        verify(objects, never()).open(anyString(), anyLong(), anyLong());
    }

    @Test
    void changedBucketIsRejectedBeforeLookingInAnotherBucket() throws Exception {
        when(objects.bucket()).thenReturn("another-bucket");
        mvc.perform(get("/api/videos/42/content")).andExpect(status().isServiceUnavailable());
        verify(objects, never()).stat(anyString());
    }

    @Test
    void objectMetadataMismatchIsRejectedBeforeReturningSuccessfulBytes() throws Exception {
        when(objects.stat(key)).thenReturn(Optional.of(new UploadObjectStore.StoredObject(6, "b".repeat(64))));
        mvc.perform(get("/api/videos/42/content")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        verify(objects, never()).open(anyString(), anyLong(), anyLong());
    }
}
