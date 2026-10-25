package com.uav.inspection.config;

import com.uav.inspection.evidence.storage.ObjectStorage;
import com.uav.inspection.evidence.storage.DownloadUrlSigner;
import com.uav.inspection.evidence.storage.S3DownloadUrlSigner;
import com.uav.inspection.evidence.storage.S3ObjectStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("storage")
public class StorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client s3Client(Environment environment) {
        try {
            URI endpoint = URI.create(required(environment, "endpoint"));
            if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
                throw new IllegalArgumentException();
            }
            if (!required(environment, "bucket").matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
                throw new IllegalArgumentException();
            }
            return S3Client.builder().endpointOverride(endpoint)
                    .region(Region.of(required(environment, "region")))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            required(environment, "access-key"), required(environment, "secret-key"))))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(
                                    environment.getRequiredProperty("inspection.storage.force-path-style", Boolean.class))
                            .chunkedEncodingEnabled(false).build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                            .socketTimeout(Duration.ofSeconds(30)))
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(45))
                            .apiCallAttemptTimeout(Duration.ofSeconds(30)))
                    .build();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid S3 endpoint, bucket, credentials or client configuration");
        }
    }

    @Bean
    ObjectStorage objectStorage(S3Client s3, Environment environment) {
        return new S3ObjectStorage(s3, required(environment, "bucket"));
    }

    @Bean(destroyMethod="close")
    S3Presigner s3Presigner(Environment environment) {
        try {
            URI endpoint=URI.create(required(environment,"endpoint"));
            if(!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                    || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null
                    || endpoint.getRawFragment()!=null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
                    || !required(environment,"bucket").matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))
                throw new IllegalArgumentException();
            return S3Presigner.builder().endpointOverride(endpoint).region(Region.of(required(environment,"region")))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            required(environment,"access-key"),required(environment,"secret-key"))))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(
                                    environment.getRequiredProperty("inspection.storage.force-path-style",Boolean.class))
                            .checksumValidationEnabled(false).chunkedEncodingEnabled(false).build()).build();
        } catch(RuntimeException failure) {
            throw new IllegalStateException("Invalid local S3 download signing configuration");
        }
    }

    @Bean
    DownloadUrlSigner downloadUrlSigner(S3Presigner presigner,Environment environment) {
        return new S3DownloadUrlSigner(presigner,required(environment,"bucket"));
    }

    private static String required(Environment environment, String name) {
        String value = environment.getRequiredProperty("inspection.storage." + name);
        if (value.isBlank()) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}
