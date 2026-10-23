package com.uav.inspection.evidence.verification;

import com.uav.inspection.common.error.BusinessException;
import com.uav.inspection.common.error.ErrorCode;
import com.uav.inspection.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationControllerTest {
    private static final String VERSION = "90000000-0000-4000-8000-000000000001";
    private final VerificationService service = mock(VerificationService.class);
    private final VerificationSettings settings = mock(VerificationSettings.class);
    private final CurrentUser actor = new CurrentUser("user", "org", "operator", List.of(), 1, 0);

    @Test
    void unauthorizedVersionIsRejectedBeforeEvenInspectingTheMultipartRequest() {
        when(settings.concurrency()).thenReturn(2);
        var controller = new VerificationController(service, settings);
        HttpServletRequest request = mock(HttpServletRequest.class);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(service).authorize(actor, VERSION);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> controller.verify(actor, VERSION, request)).errorCode());
        verifyNoInteractions(request);
        verify(service, never()).verifyCopy(any(), anyString(), any(), any(), anyLong(), any());
    }

    @Test
    void duplicateSourceParametersAreRejectedBeforeMultipartParsing() {
        when(settings.concurrency()).thenReturn(2);
        var controller = new VerificationController(service, settings);
        MultipartHttpServletRequest request = mock(MultipartHttpServletRequest.class);
        when(request.getQueryString()).thenReturn("source=COPY&source=STORED");
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> controller.verify(actor, VERSION, request)).errorCode());
        verify(request, never()).getMultiFileMap();
        verify(service, never()).verifyStored(any(), anyString());
    }

    @Test
    void multipleFilesCannotBeSilentlyReducedToTheFirstCopy() {
        when(settings.concurrency()).thenReturn(2);
        var controller = new VerificationController(service, settings);
        var request = new MockMultipartHttpServletRequest();
        request.setQueryString("source=COPY");
        request.setContentType("multipart/form-data; boundary=test");
        for (String filename : List.of("one.png", "two.png")) {
            request.addFile(new MockMultipartFile("file", filename, "image/png", new byte[8]));
            request.addPart(new MockPart("file", filename, new byte[8]) {
                @Override public void delete() { /* No physical spool file in this fixture. */ }
            });
        }
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> controller.verify(actor, VERSION, request)).errorCode());
        verify(service, never()).verifyCopy(any(), anyString(), any(), any(), anyLong(), any());
    }
}
