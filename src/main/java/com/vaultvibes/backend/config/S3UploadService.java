package com.vaultvibes.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Handles proof-of-payment file uploads to Amazon S3.
 *
 * <p>Enforces a strict allowlist of content types and a 5 MB size cap before
 * touching S3.  The S3 key pattern is:
 * <pre>contributions/{userId}/{epochMillis}-{uuid}.{ext}</pre>
 */
@Service
@Slf4j
public class S3UploadService {

    // Allowed MIME types → canonical extension
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf",  "pdf",
            "image/jpeg",       "jpg",
            "image/jpg",        "jpg",
            "image/png",        "png"
    );

    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5 MB

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.region}")
    private String region;

    /**
     * Validates and uploads a multipart file to S3.
     *
     * <p>Objects are uploaded without a public ACL — access requires a signed URL.
     * The bucket must have "Block Public Access" enabled (AWS default for new buckets).</p>
     *
     * @param userId the contributing member's UUID — used in the S3 key path
     * @param file   the uploaded file
     * @return the S3 object key (e.g. {@code contributions/{userId}/{epoch}-{uuid}.pdf})
     * @throws IllegalArgumentException if the file type or size is invalid
     * @throws IOException              if the file stream cannot be read
     */
    public String upload(UUID userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file provided.");
        }

        // Size check
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "File exceeds the 5 MB limit (received %d bytes).".formatted(file.getSize()));
        }

        // Content-type allowlist
        String contentType = normalise(file.getContentType());
        if (!ALLOWED_TYPES.containsKey(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported file type '%s'. Accepted: PDF, JPG, JPEG, PNG.".formatted(contentType));
        }

        String ext = ALLOWED_TYPES.get(contentType);
        // Key path is programmatically generated — callers cannot influence it
        String key = "contributions/%s/%d-%s.%s".formatted(
                userId, System.currentTimeMillis(), UUID.randomUUID(), ext);

        log.info("Uploading proof of payment to s3://{}/{}", bucket, key);

        try (S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .build()) {

            // No ACL set — inherits bucket policy (private by default)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .contentLength(file.getSize())
                    .build();

            s3.putObject(putRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        }

        log.info("Proof uploaded successfully — key: {}", key);
        return key;   // return the key, NOT a public URL
    }

    /** Returns the detected file type label (pdf / jpg / png). */
    public String detectFileType(MultipartFile file) {
        String contentType = normalise(file.getContentType());
        return ALLOWED_TYPES.getOrDefault(contentType, "unknown");
    }

    // -------------------------------------------------------------------------

    private static String normalise(String contentType) {
        return contentType == null ? "" : contentType.toLowerCase().trim();
    }
}

