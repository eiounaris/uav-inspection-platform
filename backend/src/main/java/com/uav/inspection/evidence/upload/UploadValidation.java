package com.uav.inspection.evidence.upload;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.evidence.storage.UploadSettings;
import com.uav.inspection.evidence.upload.UploadModels.CreateUploadRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

final class UploadValidation {
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "pdf", "application/pdf", "mp4", "video/mp4");

    private UploadValidation() {}

    static void id(String value) {
        invalid(value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    static void request(CreateUploadRequest request, UploadSettings settings) {
        invalid(request == null);
        id(request.clientRequestId());
        id(request.requirementId());
        id(request.executionId());
        String filename = request.filename();
        invalid(filename == null || filename.isBlank() || filename.length() > 255
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.codePoints().anyMatch(Character::isISOControl));
        int dot = filename.lastIndexOf('.');
        invalid(dot <= 0 || dot == filename.length() - 1);
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        invalid(!settings.allowedExtensions().contains(extension)
                || !TYPES.getOrDefault(extension, "").equals(request.contentType()));
        invalid(request.size() == null || request.size() < 1);
        if (request.size() > settings.maxFileSize()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    static String requestHash(CreateUploadRequest request) {
        MessageDigest digest = digest();
        for (String value : new String[]{"uav-upload-v1", request.requirementId(), request.executionId(),
                request.filename(), request.contentType(), Long.toString(request.size())}) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    static void exactLength(long actual, long expected) {
        if (actual > expected) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        invalid(actual < expected);
    }

    static void magic(String contentType, byte[] header, int count) {
        boolean valid = switch (contentType) {
            case "image/png" -> starts(header, count, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> starts(header, count, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "application/pdf" -> starts(header, count, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "video/mp4" -> count >= 8 && header[4] == 'f' && header[5] == 't'
                    && header[6] == 'y' && header[7] == 'p';
            default -> false;
        };
        // This is a signature check only. Complete format validation belongs to finalization.
        invalid(!valid);
    }

    private static boolean starts(byte[] header, int count, byte[] expected) {
        if (count < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (header[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    static void invalid(boolean condition) {
        if (condition) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
