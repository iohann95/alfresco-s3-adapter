package org.redpill.alfresco.s3;

import io.findify.s3mock.S3Mock;
import java.util.Random;
import org.alfresco.repo.content.ContentContext;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.redpill.alfresco.test.AbstractComponentIT;
import org.springframework.context.ApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;

/**
 *
 * @author Marcus Svartmark - Redpill Linpro AB
 */
public class S3CachingContentStoreIT extends AbstractComponentIT {

  private ContentStore s3ContentStore;
  private S3ContentStore s3ContentStoreImpl;
  private ContentStore cachingContentStore;
  private S3Mock api;
  private String bucket;

  @Before
  public void setUp() {
    ApplicationContext ctx = getApplicationContext();
    s3ContentStore = (ContentStore) ctx.getBean("redpill.defaultS3ContentStore");
    s3ContentStoreImpl = (S3ContentStore) ctx.getBean("redpill.defaultS3ContentStore");
    Random r = new Random();
    int randomPort = 20000 + r.nextInt(30000);
    //Start an s3 mock
    api = new S3Mock.Builder().withPort(randomPort).withInMemoryBackend().build();
    api.start();
    bucket = "alftestbucket" + System.currentTimeMillis();

    s3ContentStoreImpl.setBucketName(bucket);
    s3ContentStoreImpl.setEndpoint("http://localhost:" + randomPort);
    s3ContentStoreImpl.testInit();
    S3Client s3Client = s3ContentStoreImpl.getS3Client();
    s3Client.createBucket(bucket);

    cachingContentStore = (ContentStore) ctx.getBean("redpill.defaultCachedS3BackedContentStore");
  }

  @After
  public void tearDown() {
    S3Client s3Client = s3ContentStoreImpl.getS3Client();
    s3Client.deleteBucket(bucket);
    api.stop(); 
  }

  @Test
  public void testSimpleTextContent() {
    final String TEST_TEXT_CONTENT = "test";
    ContentContext context = new ContentContext(null, null);
    ContentWriter writer = cachingContentStore.getWriter(context);
    writer.putContent(TEST_TEXT_CONTENT);
    String contentUrl = writer.getContentUrl();
    try {
      ContentReader reader = cachingContentStore.getReader(contentUrl);
      String contentString = reader.getContentString();
      assertEquals(TEST_TEXT_CONTENT, contentString);

    } finally {
      boolean delete = cachingContentStore.delete(contentUrl);
      assertTrue(delete);
    }

  }
}
