package com.uav.inspection.evidence.cleanup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Only exact server-generated attempt namespaces are collectible. */
final class CleanupKey {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    record Parsed(String uploadId,String finalToken) {}

    static Parsed parse(String key) {
        if(key==null || key.length()>512) return null;
        String[] pieces=key.split("/",-1);
        if(pieces.length==3 && "evidence-files".equals(pieces[0]) && pieces[1].matches(UUID) && pieces[2].matches(UUID))
            return new Parsed(pieces[1],pieces[2]);
        if(pieces.length==4 && "upload-parts".equals(pieces[0]) && pieces[1].matches(UUID)
                && pieces[2].matches("[1-9][0-9]{0,4}") && Integer.parseInt(pieces[2])<=10000 && pieces[3].matches(UUID))
            return new Parsed(pieces[1],null);
        return null;
    }

    static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
