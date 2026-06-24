package org.redpill.alfresco.s3;

import org.alfresco.repo.content.AbstractContentWriter;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.util.GUID;
import org.alfresco.util.TempFileProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

/**
 * S3 content writer
 *
 * @author Marcus Svartmark
 */
public class S3ContentWriter extends AbstractContentWriter {

  private static final Log LOG = LogFactory.getLog(S3ContentWriter.class);

  private final S3Client client;
  private final S3TransferManager transferManager;
  private final long multipartUploadThreshold;
  private final String key;
  private final String bucketName;
  private File tempFile;
  private long size;

  public S3ContentWriter(String bucketName, String key, String contentUrl, ContentReader existingContentReader, S3Client client, S3TransferManager transferManager, long multipartUploadThreshold) {
    super(contentUrl, existingContentReader);
    this.key = key;
    this.client = client;
    this.transferManager = transferManager;
    this.multipartUploadThreshold = multipartUploadThreshold;
    this.bucketName = bucketName;
    addListener(new S3WriteStreamListener(this));
  }

  @Override
  protected ContentReader createReader() throws ContentIOException {
    return new S3ContentReader(key, getContentUrl(), client, bucketName);
  }

  @Override
  protected WritableByteChannel getDirectWritableChannel() throws ContentIOException {
    try {
      String uuid = GUID.generate(); 
      if (LOG.isDebugEnabled()){
        LOG.debug("S3ContentWriter Creating Temp File: uuid=" + uuid);
      }
      tempFile = TempFileProvider.createTempFile(uuid, ".bin");
      OutputStream os = new FileOutputStream(tempFile);
      if (LOG.isDebugEnabled()){
        LOG.debug("S3ContentWriter Returning Channel to Temp File: uuid=" + uuid);
      }
      return Channels.newChannel(os);
    } catch (Throwable e) {
      throw new ContentIOException("S3ContentWriter.getDirectWritableChannel(): Failed to open channel. " + this, e);
    }
  }

  @Override
  public long getSize() {
    return this.size;
  }

  public void setSize(long size) {
    this.size = size;
  }

  public String getBucketName() {
    return bucketName;
  }

  public String getKey() {
    return key;
  }

  public File getTempFile() {
    return tempFile;
  }

  public S3Client getClient() {
    return client;
  }

  public S3TransferManager getTransferManager() {
    return transferManager;
  }

  public long getMultipartUploadThreshold() {
    return multipartUploadThreshold;
  }
}
