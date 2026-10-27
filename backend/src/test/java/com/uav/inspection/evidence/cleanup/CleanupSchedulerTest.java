package com.uav.inspection.evidence.cleanup;

import com.uav.inspection.common.temp.TemporaryFileManager;
import com.uav.inspection.evidence.storage.UploadSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CleanupSchedulerTest {
    @Test void disabledSchedulerDoesNotSweepOrDispatchObjectWork() {
        var processor=mock(CleanupProcessor.class);var temporary=mock(TemporaryFileManager.class);
        var uploads=mock(UploadSettings.class);when(uploads.tempDirectory()).thenReturn(Path.of(".local/test-private"));
        var environment=new MockEnvironment().withProperty("inspection.cleanup.scheduler-enabled","false");
        try(var scheduler=new CleanupScheduler(processor,temporary,uploads,new CleanupSettings(environment),environment)) {
            scheduler.tick();verifyNoInteractions(processor,temporary);
        }
    }

    @Test void blockedObjectIoDoesNotBlockTicksOrQueueExtraCycles() throws Exception {
        var processor=mock(CleanupProcessor.class);var temporary=mock(TemporaryFileManager.class);
        var uploads=mock(UploadSettings.class);when(uploads.tempDirectory()).thenReturn(Path.of(".local/test-private"));
        when(temporary.sweep(any(),any(),anyInt())).thenReturn(new TemporaryFileManager.SweepResult(0,0,0,0,0));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{
            calls.incrementAndGet();entered.countDown();
            try {release.await();} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            return null;
        }).when(processor).runOnce();
        var environment=new MockEnvironment();
        try(var scheduler=new CleanupScheduler(processor,temporary,uploads,new CleanupSettings(environment),environment)) {
            scheduler.tick();
            try {assertTrue(entered.await(3,TimeUnit.SECONDS));for(int i=0;i<20;i++)scheduler.tick();assertEquals(1,calls.get());}
            finally {release.countDown();}
        }
        assertEquals(1,calls.get());
    }
}
