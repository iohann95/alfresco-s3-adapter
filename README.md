# Alfresco S3 Adapter

This project is meant to provide tools to use s3 as a storage solution for Alfresco. It can be configured to work as a main content store if you are a community user. If you are an enterprise customer you can configure it to work with content store selector or as a main content store.

## History

 * Forked from https://github.com/rmberg/alfresco-s3-adapter which originally was migrated from the `alfresco-cloud-store` project at https://code.google.com/p/alfresco-cloud-store/.
 * Apache License 2.0
 * Uses Amazon SDK 1.x
 * Uses Alfresco SDK 3.0 and tested with Alfresco 5.2
 * Pull Requests / Issues / Contributions are welcomed!
 * Use Findify s3mock for testing
 
# Alfresco AIO Project - SDK 4.0

This is an All-In-One (AIO) project for Alfresco SDK 4.0.

Run with `./run.sh build_start` or `./run.bat build_start` and verify that it

 * Runs Alfresco Content Service (ACS)
 * Runs Alfresco Share
 * Runs Alfresco Search Service (ASS)
 * Runs PostgreSQL database
 * Deploys the JAR assembled modules
 
All the services of the project are now run as docker containers. The run script offers the next tasks:

 * `build_start`. Build the whole project, recreate the ACS and Share docker images, start the dockerised environment composed by ACS, Share, ASS and 
 PostgreSQL and tail the logs of all the containers.
 * `build_start_it_supported`. Build the whole project including dependencies required for IT execution, recreate the ACS and Share docker images, start the 
 dockerised environment composed by ACS, Share, ASS and PostgreSQL and tail the logs of all the containers.
 * `start`. Start the dockerised environment without building the project and tail the logs of all the containers.
 * `stop`. Stop the dockerised environment.
 * `purge`. Stop the dockerised container and delete all the persistent data (docker volumes).
 * `tail`. Tail the logs of all the containers.
 * `reload_share`. Build the Share module, recreate the Share docker image and restart the Share container.
 * `reload_acs`. Build the ACS module, recreate the ACS docker image and restart the ACS container.
 * `build_test`. Build the whole project, recreate the ACS and Share docker images, start the dockerised environment, execute the integration tests from the
 `integration-tests` module and stop the environment.
 * `test`. Execute the integration tests (the environment must be already started).

# Few things to notice

 * No parent pom
 * No WAR projects, the jars are included in the custom docker images
 * No runner project - the Alfresco environment is now managed through [Docker](https://www.docker.com/)
 * Standard JAR packaging and layout
 * Works seamlessly with Eclipse and IntelliJ IDEA
 * JRebel for hot reloading, JRebel maven plugin for generating rebel.xml [JRebel integration documentation]
 * AMP as an assembly
 * Persistent test data through restart thanks to the use of Docker volumes for ACS, ASS and database data
 * Integration tests module to execute tests against the final environment (dockerised)
 * Resources loaded from META-INF
 * Web Fragment (this includes a sample servlet configured via web fragment)

## Installation

 * This module does not work stand alone out of the box but will require further configuration. Either install it as an amp in your alfresco installation and configure it using spring xml files in shared/classes/extension/s3-override-context.xml.
 * If you have your own amp project you can include the s3 module as a dependency.
 
### Use as content store selector
The following code snippet can be used to configure the module to work in an enterprise setup with a content store selector.
 
 ```
 <?xml version='1.0' encoding='UTF-8'?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
          http://www.springframework.org/schema/beans/spring-beans.xsd">
  <!-- Change the default store to a content store selector-->
  <bean id="contentService" parent="baseContentService">
    <property name="store">
      <ref bean="storeSelectorContentStore" />
    </property>
  </bean>
  
  <!-- Configure the content store selector to use the file store as a default store and s3 as a backup -->
  <bean id="storeSelectorContentStore" parent="storeSelectorContentStoreBase">
    <property name="defaultStoreName">
      <value>default</value>
    </property>
    <property name="storesByName">
      <map>
        <entry key="default">
          <ref bean="fileContentStore" />
        </entry>
        <entry key="s3main">
          <ref bean="redpill.defaultCachedS3BackedContentStore" />
        </entry>
      </map>
    </property>
  </bean>
  
  <!-- Tell the system to delete using the contentSelectorContentStore as well -->
  <bean id="contentStoresToClean" class="java.util.ArrayList" >
    <constructor-arg>
      <list>
        <ref bean="fileContentStore" />
        <ref bean="redpill.defaultCachedS3BackedContentStore" />
      </list>
    </constructor-arg>
  </bean>
</beans> 
```
 
### Use as a main content store
The following code snippet can be used to configure the module to work as a caching content store backed by s3.

```
<?xml version='1.0' encoding='UTF-8'?>
<beans xmlns="http://www.springframework.org/schema/beans" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">
  <!--  Caching Content Store -->
  <bean id="fileContentStore" class="org.alfresco.repo.content.caching.CachingContentStore" init-method="init">
    <property name="backingStore" ref="redpill.defaultS3ContentStore"/>
    <property name="cache" ref="redpill.defaultS3ContentCache"/>
    <property name="cacheOnInbound" value="${system.content.caching.cacheOnInbound}"/>
    <property name="quota" ref="redpill.defaultS3QuotaManager" />
  </bean>
</beans> 
```

## Configuration
* After installing the package you will need to add some properties to your `alfresco-global.properties` file:
 
```
# Your AWS credentials
# Alternatively these can be set in the standard locations the AWS SDK will search for them
# For example: if you are running on an EC2 instance and are using IAM roles, you can leave these blank and the credentials
# for the role will be used.
aws.accessKey=
aws.secretKey=

# The AWS Region (US-EAST-1) will be used by default if not specified
aws.regionName=us-east-1

# The S3 bucket name to use as the content store
aws.s3.bucketName=

# The endpoint url if other than AWS (for other S3-compatible vendors)
aws.s3.endpoint=

# The relative path (S3 KEY) within the bucket to use as the content store (useful if the bucket is not dedicated to alfresco content)
aws.s3.rootDirectory=/alfresco/contentstore

# Signing version for s3 sdk. If empty this will be the default for the current sdk version. When working with custom s3 providers, this might have to be changed. Currently allowed values are <empty>, AWSS3V4SignerType and S3SignerType.
aws.s3.signatureVersion=

# Connection timeout for the s3 client
aws.s3.client.connectionTimeout=50000
# Connection time to live in the s3 client connection pool
aws.s3.client.connectionTTL=60000
# Number of retries on error in the s3 client
aws.s3.client.maxErrorRetry=5
# Multipart upload threshold in bytes.
# 1099511627776 = 1tb
# 1073741824 = 1gb
# 104857600 = 100mb
# 16777216 = 16mb
aws.s3.client.multipartUploadThreshold=16777216

# The cache size
defaultS3QuotaManager.maxUsageMB=4096
# The max file size in MB to store in cache 0 means no limit
defaultS3QuotaManager.maxFileSizeMB=0
# Content cache dir
defaultS3ContentCache.cachedcontent=/tmp/cachedcontent
```
 
## Troubleshooting ##
Enable debug logging in Log4j2 by adding these lines to your log4j2.properties file

```
logger.s3.name=org.redpill.alfresco.s3
logger.s3.level=trace
logger.awsRequestId.name=com.amazonaws.requestId
logger.awsRequestId.level=debug
logger.awsRequest.name=com.amazonaws.request
logger.awsRequest.level=debug
``` 
