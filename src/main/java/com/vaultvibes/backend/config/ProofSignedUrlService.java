package com.vaultvibes.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * Generates short-lived pre-signed S3 URLs for proof-of-payment files.
 *
 * <p>URLs expire after {@value #EXPIRY_MINUTES} minutes.  PDF proofs are served with
 * {@code Content-Disposition: attachment} so the browser prompts a download rather
 * than rendering the document inline — preventing sensitive financial documents from
 * being displayed in a browser tab.</p>
 */
@Service
@Slf4j
public class ProofSignedUrlService {

    public static final int EXPIRY_MINUTES = 5;
    private static final Duration EXPIRY = Duration.ofMinutes(EXPIRY_MINUTES);

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.region}")
    private String region;

    /**
     * Generates a pre-signed GET URL for the given S3 key.
     *
     * @param key      the S3 object key (e.g. {@code contributions/{userId}/...})
     * @param fileType the canonical type label returned by S3UploadService (pdf / jpg / png)
     * @return a temporary HTTPS URL valid for {@value #EXPIRY_MINUTES} minutes
     * @throws IllegalStateException if URL generation fails
     */
    public String generateSignedUrl(String key, String fileType) {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(region))
                .build()) {

            GetObjectRequest.Builder getReqBuilder = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key);

            // Force download for PDFs — prevents inline browser rendering of financial docs
            if ("pdf".equalsIgnoreCase(fileType)) {
                getReqBuilder.responseContentDisposition("attachment");
            }

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(EXPIRY)
                    .getObjectRequest(getReqBuilder.build())
                    .build();

            String url = presigner.presignGetObject(presignRequest).url().toString();
            log.debug("Generated signed URL for key {} (expires {}m)", key, EXPIRY_MINUTES);
            return url;

        } catch (Exception ex) {
            log.error("Failed to generate signed URL for key {}: {}", key, ex.getMessage(), ex);
            throw new IllegalStateException("Could not generate proof access URL.");
        }
    }
}
