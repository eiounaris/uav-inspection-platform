package com.uav.inspection.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageEventTest {
    private static final MessageEvent EVENT=new MessageEvent("11000000-0000-4000-8000-000000000001",
            MessageEvent.FILE_FINALIZE_REQUESTED,"22000000-0000-4000-8000-000000000001","trace-test");
    private final String json=JsonMapper.builder().build().writeValueAsString(EVENT);

    @Test
    void canonicalEnvelopeAndReorderedFieldsHaveTheSameFourFieldIdentity() {
        assertEquals(EVENT,decode(json));
        assertEquals(EVENT,decode("{\"traceId\":\"trace-test\",\"bizId\":\""+EVENT.bizId()+"\",\"eventType\":\""+EVENT.eventType()+"\",\"eventId\":\""+EVENT.eventId()+"\"}"));
    }

    @Test
    void duplicateKeysUnknownFieldsMissingFieldsAndNonStringValuesAreNotAccepted() {
        for(String value:List.of(json.substring(0,json.length()-1)+",\"traceId\":\"trace-test\"}",
                json.substring(0,json.length()-1)+",\"extra\":null}",json.replace("\"trace-test\"","null"),
                json.replace("\"trace-test\"","123"),"{}","[]",json+" {}",json+" null")) {
            assertThrows(InvalidMessageException.class,()->decode(value));
        }
    }

    @Test
    void byteLimitIsExactAndInvalidUtf8OrSensitiveParserDetailsNeverEscape() {
        byte[] valid=json.getBytes(StandardCharsets.UTF_8);
        assertEquals(EVENT,decode(" ".repeat(4096-valid.length)+json));
        assertThrows(InvalidMessageException.class,()->decode(" ".repeat(4097-valid.length)+json));
        assertThrows(InvalidMessageException.class,()->MessageEvent.decode(new byte[]{(byte)0xc3,(byte)0x28}));
        var failure=assertThrows(InvalidMessageException.class,()->decode("{\"password\":\"sensitive-value\""));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("sensitive-value"));
        assertThrows(InvalidMessageException.class,()->MessageEvent.decode(null));
    }

    private static MessageEvent decode(String value) { return MessageEvent.decode(value.getBytes(StandardCharsets.UTF_8)); }
}
