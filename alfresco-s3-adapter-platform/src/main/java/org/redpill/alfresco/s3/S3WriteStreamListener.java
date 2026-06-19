package org.redpill.alfresco.s3;

import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentStreamListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

/**
 * Stream listener which is used to copy the temp file contents into S3
 */
public class S3WriteStreamListener implements ContentStreamListener {

  private static final Log LOG = LogFactory.getLog(S3WriteStreamListener.class);

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
      FileUpload upload = writer.getTransferManager().uploadFile(
          UploadFileRequest.builder()
              .putObjectRequest(PutObjectRequest.builder()
                  .bucket(writer.getBucketName())
                  .key(writer.getKey())
                  .build())
              .source(writer.getTempFile().toPath())
              .build());
      upload.completionFuture().join();
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
}
