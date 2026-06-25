package org.redpill.alfresco.s3;

import org.alfresco.repo.content.AbstractContentStore;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.repo.content.ContentStoreCreatedEvent;
import org.alfresco.repo.content.UnsupportedContentUrlException;
import org.alfresco.repo.content.filestore.FileContentStore;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.util.GUID;
import org.alfresco.util.Pair;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

/**
 * A s3 content store
 *
 * @author Marcus Svartmark - Redpill Linpro
 */
public class S3ContentStore extends AbstractContentStore
  implements ApplicationContextAware, ApplicationListener<ApplicationEvent> {

  private static final Logger LOG = LogManager.getLogger(S3ContentStore.class);
  private ApplicationContext applicationContext;

  private S3Client s3Client;
  private S3AsyncClient s3AsyncClient;
  private S3TransferManager transferManager;

  private String accessKey;
  private String secretKey;
  private String bucketName;
  private String regionName;
  private String rootDirectory;
  private String endpoint;
  private String signatureVersion;
  private int connectionTimeout = 10000;
  private int maxErrorRetry = 1;
  private long connectionTTL = 60000L;
  private long multipartUploadThreshold = 16777216L;

  /**
   * @param multipartUploadThreshold The multipart upload threshold
   */
  public void setMultipartUploadThreshold(long multipartUploadThreshold) {
    this.multipartUploadThreshold = multipartUploadThreshold;
  }

  /**
   * @param connectionTTL set TTL for connection
   */
  public void setConnectionTTL(long connectionTTL) {
    this.connectionTTL = connectionTTL;
  }

  /**
   * @param maxErrorRetry set max retries
   */
  public void setMaxErrorRetry(int maxErrorRetry) {
    this.maxErrorRetry = maxErrorRetry;
  }

  /**
   * @param connectionTimeout set connection timeout
   */
  public void setConnectionTimeout(int connectionTimeout) {
    this.connectionTimeout = connectionTimeout;
  }

  @Override
  public boolean isWriteSupported() {
    return true;
  }

  @Override
  public ContentReader getReader(String contentUrl) {

    String key = makeS3Key(contentUrl);
    return new S3ContentReader(key, contentUrl, s3Client, bucketName);

  }

  /**
   * Get the currently used s3 client
   *
   * @return Returns a s3 client
   */
  public S3Client getS3Client() {
    return s3Client;
  }

  /**
   * Initialize the content store
   */
  public void init() {
    AwsCredentialsProvider credentialsProvider;
    if (!StringUtils.isEmpty(signatureVersion)) {
      LOG.debug("Using client override for signatureVersion: " + signatureVersion);
    }

    if (StringUtils.isNotBlank(this.accessKey) && StringUtils.isNotBlank(this.secretKey)) {
      LOG.debug("Found credentials in properties file");
      credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(this.accessKey, this.secretKey));

    } else {
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("AWS Credentials not specified in properties, will fallback to credentials provider");
        }
        credentialsProvider = ProfileCredentialsProvider.create();
        // Preserve v1 behavior by forcing credential resolution during init and
        // falling back to anonymous immediately if no profile credentials exist.
        credentialsProvider.resolveCredentials();
      } catch (Exception e) {
        LOG.error("Can not find AWS Credentials. Trying anonymous.", e);
        credentialsProvider = AnonymousCredentialsProvider.create();
      }
    }

    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
            .connectionTimeout(Duration.ofMillis(connectionTimeout))
            .connectionTimeToLive(Duration.ofMillis(connectionTTL));

        ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
          .retryPolicy(RetryPolicy.builder().numRetries(maxErrorRetry).build())
          .build();

            S3ClientBuilder s3Builder = S3Client.builder()
            .credentialsProvider(credentialsProvider)
            .httpClientBuilder(httpClientBuilder)
            .overrideConfiguration(overrideConfiguration)
            .serviceConfiguration(S3Configuration.builder().build());

            S3AsyncClientBuilder s3AsyncBuilder = S3AsyncClient.builder()
          .credentialsProvider(credentialsProvider)
          .overrideConfiguration(overrideConfiguration)
          .multipartEnabled(true)
          .multipartConfiguration(conf -> conf.thresholdInBytes(multipartUploadThreshold))
          .serviceConfiguration(S3Configuration.builder().build());

    if (!"".equals(endpoint)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Using custom endpoint" + endpoint);
      }
      s3Client = s3Builder
              .endpointOverride(URI.create(endpoint))
              .region(Region.of(regionName))
              .build();
            s3AsyncClient = s3AsyncBuilder
              .endpointOverride(URI.create(endpoint))
              .region(Region.of(regionName))
              .build();

    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Using default Amazon S3 endpoint with region " + regionName);
      }
      s3Client = s3Builder
              .region(Region.of(regionName))
              .build();
      s3AsyncClient = s3AsyncBuilder
              .region(Region.of(regionName))
              .build();
    }

    transferManager = S3TransferManager.builder().s3Client(s3AsyncClient).build();
  }

  /**
   * Test init method to use for local testing with findify s3 mock
   */
  public void testInit() {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Using test init");
      LOG.debug("Using custom endpoint" + endpoint);
      LOG.debug("Using default Amazon S3 endpoint with region " + regionName);
    }
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
          .connectionTimeout(Duration.ofMillis(connectionTimeout))
          .connectionTimeToLive(Duration.ofMillis(connectionTTL));

    if (!StringUtils.isEmpty(signatureVersion)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Using client override for signatureVersion: " + signatureVersion);
      }
    }

    s3Client = S3Client.builder()
          .credentialsProvider(AnonymousCredentialsProvider.create())
          .httpClientBuilder(httpClientBuilder)
          .endpointOverride(URI.create(endpoint))
          .region(Region.of(regionName))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();

    s3AsyncClient = S3AsyncClient.builder()
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(regionName))
            .multipartEnabled(true)
            .multipartConfiguration(conf -> conf.thresholdInBytes(multipartUploadThreshold))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();

    transferManager = S3TransferManager.builder().s3Client(s3AsyncClient).build();
  }

  public void setAccessKey(String accessKey) {
    this.accessKey = accessKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  public void setRegionName(String regionName) {
    this.regionName = regionName;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public void setSignatureVersion(String signatureVersion) {
    this.signatureVersion = signatureVersion;
  }

  public void setRootDirectory(String rootDirectory) {

    String dir = rootDirectory;
    if (dir.startsWith("/")) {
      dir = dir.substring(1);
    }

    this.rootDirectory = dir;
  }

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }

  @Override
  protected ContentWriter getWriterInternal(ContentReader existingContentReader, String newContentUrl) {

    String contentUrl = newContentUrl;

    if (StringUtils.isBlank(contentUrl)) {
      contentUrl = createNewUrl();
    }

    String key = makeS3Key(contentUrl);

    return new S3ContentWriter(bucketName, key, contentUrl, existingContentReader, s3Client, transferManager, multipartUploadThreshold);

  }

  /**
   * Create a hashed URL for storage
   *
   * @return Returns a string containing the URL for storage
   */
  public static String createNewUrl() {

    Calendar calendar = new GregorianCalendar();
    int year = calendar.get(Calendar.YEAR);
    int month = calendar.get(Calendar.MONTH) + 1;  // 0-based
    int day = calendar.get(Calendar.DAY_OF_MONTH);
    int hour = calendar.get(Calendar.HOUR_OF_DAY);
    int minute = calendar.get(Calendar.MINUTE);
    // create the URL
    StringBuilder sb = new StringBuilder(20);
    sb.append(FileContentStore.STORE_PROTOCOL)
            .append(ContentStore.PROTOCOL_DELIMITER)
            .append(year).append('/')
            .append(month).append('/')
            .append(day).append('/')
            .append(hour).append('/')
            .append(minute).append('/')
            .append(GUID.generate()).append(".bin");
    String newContentUrl = sb.toString();
    // done
    return newContentUrl;

  }

  private String makeS3Key(String contentUrl) {
    // take just the part after the protocol
    Pair<String, String> urlParts = super.getContentUrlParts(contentUrl);
    String protocol = urlParts.getFirst();
    String relativePath = urlParts.getSecond();
    // Check the protocol
    if (!protocol.equals(FileContentStore.STORE_PROTOCOL)) {
      throw new UnsupportedContentUrlException(this, protocol + PROTOCOL_DELIMITER + relativePath);
    }

    return rootDirectory + "/" + relativePath;

  }

  @Override
  public boolean delete(String contentUrl) {

    try {
      String key = makeS3Key(contentUrl);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Deleting object from S3 with url: " + contentUrl + ", key: " + key);
      }
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
      return true;
    } catch (Exception e) {
      LOG.error("Error deleting S3 Object", e);
    }
    return false;
  }

  /**
   * Publishes an event to the application context that will notify any
   * interested parties of the existence of this content store.
   *
   * @param context the application context
   * @param extendedEventParams
   */
  private void publishEvent(ApplicationContext context, Map<String, Serializable> extendedEventParams) {
    context.publishEvent(new ContentStoreCreatedEvent(this, extendedEventParams));
  }

  @Override
  public void onApplicationEvent(ApplicationEvent event) {
    // Once the context has been refreshed, we tell other interested beans about the existence of this content store
    // (e.g. for monitoring purposes)
    if (event instanceof ContextRefreshedEvent && event.getSource() == this.applicationContext) {
      publishEvent(((ContextRefreshedEvent) event).getApplicationContext(), Collections.<String, Serializable>emptyMap());
    }
  }
}
