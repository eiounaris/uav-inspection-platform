package com.uav.inspection.config;

import com.uav.inspection.evidence.verification.VerificationSettings;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

@Configuration(proxyBeanMethods = false)
@Profile("database & storage & ledger")
public class VerificationHttpConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(VerificationSettings settings) {
        try {
            var directory = settings.tempDirectory().resolve("http");
            var permissions = PosixFilePermissions.fromString("rwx------");
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(permissions));
            if (!Files.getPosixFilePermissions(directory).equals(permissions)) throw new IOException();
            // Parts spool to a private directory instead of growing the request heap.
            return new MultipartConfigElement(directory.toString(), settings.maxFileSize(),
                    Math.addExact(settings.maxFileSize(), 1048576L), 0);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Private verification multipart directory or limits are invalid");
        }
    }

    @Bean(name = "multipartResolver")
    StandardServletMultipartResolver multipartResolver() {
        var resolver = new StandardServletMultipartResolver();
        resolver.setResolveLazily(true);
        resolver.setStrictServletCompliance(true);
        return resolver;
    }
}
