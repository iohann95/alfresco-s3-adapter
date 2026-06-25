package org.redpill.alfresco.s3;

import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentStreamListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Stream listener which is used to copy the temp file contents into S3
 */
public class S3WriteStreamListener implements ContentStreamListener {

  private static final Logger LOG = LogManager.getLogger(S3WriteStreamListener.class);
  private static final int MIN_MULTIPART_PART_SIZE = 5 * 1024 * 1024;

  private final S3ContentWriter writer;

  public S3WriteStreamListener(S3ContentWriter writer) {

    this.writer = writer;

  }

  @Override
  public void contentStreamClosed() throws ContentIOException {

    File file = writer.getTempFile();
    long size = file.length();
    writer.setSize(size);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Writing to s3://" + writer.getBucketName() + "/" + writer.getKey());
    }
    try {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Waiting for upload result for bucket " + writer.getBucketName() + " with key " + writer.getKey());
      }
      if (size >= writer.getMultipartUploadThreshold()) {
        uploadMultipart(file, size);
      } else {
        try (InputStream inputStream = new FileInputStream(file)) {
          writer.getClient().putObject(
              PutObjectRequest.builder()
                  .bucket(writer.getBucketName())
                  .key(writer.getKey())
                  .build(),
              RequestBody.fromInputStream(inputStream, size));
        }
      }
      if (LOG.isTraceEnabled()) {
        LOG.trace("Upload completed for bucket " + writer.getBucketName() + " with key " + writer.getKey());
      }
    } catch (Exception e) {
      throw new ContentIOException("S3WriterStreamListener Failed to Upload File for bucket " + writer.getBucketName() + " with key " + writer.getKey(), e);
    } finally {
      //Remove the temp file
      writer.getTempFile().delete();
    }

  }

  private void uploadMultipart(File file, long size) throws Exception {
    String uploadId = null;
    int partSize = (int) Math.max(MIN_MULTIPART_PART_SIZE, Math.min(Integer.MAX_VALUE, writer.getMultipartUploadThreshold()));
    List<CompletedPart> completedParts = new ArrayList<>();

    try {
      CreateMultipartUploadResponse multipartUpload = writer.getClient().createMultipartUpload(
          CreateMultipartUploadRequest.builder()
              .bucket(writer.getBucketName())
              .key(writer.getKey())
              .build());
      uploadId = multipartUpload.uploadId();

      try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
        int partNumber = 1;
        long uploaded = 0;
        while (uploaded < size) {
          int currentPartSize = (int) Math.min(partSize, size - uploaded);
          byte[] chunk = new byte[currentPartSize];
          raf.readFully(chunk);

          UploadPartResponse uploadPartResponse = writer.getClient().uploadPart(
              UploadPartRequest.builder()
                  .bucket(writer.getBucketName())
                  .key(writer.getKey())
                  .uploadId(uploadId)
                  .partNumber(partNumber)
                  .contentLength((long) currentPartSize)
                  .build(),
              RequestBody.fromBytes(chunk));

          completedParts.add(CompletedPart.builder()
              .partNumber(partNumber)
              .eTag(uploadPartResponse.eTag())
              .build());

          uploaded += currentPartSize;
          partNumber++;
        }
      }

      writer.getClient().completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(writer.getBucketName())
              .key(writer.getKey())
              .uploadId(uploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
              .build());
    } catch (Exception e) {
      if (uploadId != null) {
        try {
          writer.getClient().abortMultipartUpload(AbortMultipartUploadRequest.builder()
              .bucket(writer.getBucketName())
              .key(writer.getKey())
              .uploadId(uploadId)
              .build());
        } catch (Exception abortException) {
          LOG.warn("Failed to abort multipart upload for bucket " + writer.getBucketName() + " with key " + writer.getKey(), abortException);
        }
      }
      throw e;
    }
  }
}
