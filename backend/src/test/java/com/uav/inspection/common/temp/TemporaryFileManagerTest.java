package com.uav.inspection.common.temp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryFileManagerTest {
    @TempDir Path directory;
    private final TemporaryFileManager manager=new TemporaryFileManager();
    private static final Duration RETENTION=Duration.ofSeconds(60);

    @AfterEach void closeScans() { manager.close(); }

    @Test
    void boundedBatchesAdvancePastUncollectibleEntriesInsteadOfRestartingAtTheBeginning() throws Exception {
        for (int i=0;i<12;i++) old(write("upload-"+i+".part"));
        List<Path> actualOrder;
        try (var entries=Files.list(directory)) { actualOrder=entries.toList(); }
        // Updating metadata preserves the directory's actual enumeration order.
        for (Path first:actualOrder.subList(0,3)) Files.setLastModifiedTime(first,FileTime.from(Instant.now()));
        var first=manager.sweep(directory,RETENTION,3);
        assertEquals(3,first.inspected());assertEquals(0,first.deleted());
        for (int batch=0;batch<12;batch++) assertTrue(manager.sweep(directory,RETENTION,3).inspected()<=3);
        for (Path young:actualOrder.subList(0,3)) assertTrue(Files.exists(young));
        for (Path residue:actualOrder.subList(3,actualOrder.size())) assertFalse(Files.exists(residue));
    }

    @Test
    void replacingTheConfiguredDirectoryClosesItsOldScanBeforeOpeningTheReplacement() throws Exception {
        Path scan=directory.resolve("scan");
        Files.createDirectory(scan,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(scan.resolve("foreign-one"),"kept");Files.writeString(scan.resolve("foreign-two"),"kept");
        assertEquals(1,manager.sweep(scan,RETENTION,1).inspected());
        Path moved=directory.resolve("moved");Files.move(scan,moved);
        Files.createDirectory(scan,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path residue=scan.resolve("upload-999.part");Files.writeString(residue,"residue");old(residue);
        assertEquals(1,manager.sweep(scan,RETENTION,100).deleted());
        assertFalse(Files.exists(residue));assertEquals("kept",Files.readString(moved.resolve("foreign-two")));
    }

    @Test
    void shutdownClosesDirectoryCursorsWithoutReleasingAProducersActiveLease() throws Exception {
        Files.writeString(directory.resolve("foreign-one"),"kept");Files.writeString(directory.resolve("foreign-two"),"kept");
        var handle=manager.create(directory,"upload-",".part");old(handle.path());
        try {
            manager.sweep(directory,RETENTION,1);
            assertTrue(directoryDescriptors(List.of(directory))>0);
            manager.close();manager.close();
            assertEquals(0,directoryDescriptors(List.of(directory)));
            assertThrows(IOException.class,()->manager.create(directory,"upload-",".part"));
            assertEquals(1,manager.sweep(directory,RETENTION,100).failed());
            try (var other=new TemporaryFileManager()) {
                assertTrue(other.sweep(directory,RETENTION,100).locked()>0);
                assertTrue(Files.exists(handle.path()));
            }
        } finally { handle.close(); }
    }

    @Test
    void manyConfiguredDirectoriesCannotAccumulateUnboundedScanDescriptors() throws Exception {
        List<Path> paths=new ArrayList<>();
        for (int i=0;i<20;i++) {
            Path parent=directory.resolve("private-"+i);
            Files.createDirectory(parent,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.writeString(parent.resolve("foreign-one"),"kept");Files.writeString(parent.resolve("foreign-two"),"kept");
            paths.add(parent);manager.sweep(parent,RETENTION,1);
        }
        assertEquals(0,directoryDescriptors(paths.subList(0,4)));
        assertTrue(directoryDescriptors(paths.subList(4,20))>0);
        // Linux's secure directory stream owns at most two descriptors per retained directory.
        assertTrue(directoryDescriptors(paths)<=32);
        manager.close();assertEquals(0,directoryDescriptors(paths));
    }

    private static long directoryDescriptors(List<Path> directories) throws IOException {
        try (var descriptors=Files.list(Path.of("/proc/self/fd"))) {
            return descriptors.filter(fd->{
                try { return directories.contains(Files.readSymbolicLink(fd)); }
                catch (IOException closedConcurrently) { return false; }
            }).count();
        }
    }

    @Test
    void createsPrivateDataAndLeaseAndClosesBothIdempotently() throws Exception {
        Path parent=directory.resolve("private");
        var handle=manager.create(parent,"upload-",".part");
        Path data=handle.path(),lease=sidecar(data);
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(parent));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(data));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(lease));
        handle.close();handle.close();
        assertFalse(Files.exists(data));assertFalse(Files.exists(lease));
    }

    @Test
    void everyProducerKindIsProtectedAcrossManagerInstancesDespiteOtherDataDescriptorsClosing() throws Exception {
        for (String kind:List.of("upload","finalize","verification")) {
            try (var handle=manager.create(directory,kind+"-",kind.equals("upload")?".part":".tmp")) {
                Files.writeString(handle.path(),"active bytes");
                assertEquals("active bytes",Files.readString(handle.path()));
                old(handle.path());
                var result=new TemporaryFileManager().sweep(directory,RETENTION,100);
                assertTrue(result.locked()>0);assertEquals(0,result.deleted());assertEquals(0,result.failed());
                assertEquals("active bytes",Files.readString(handle.path()));
            }
        }
        try (var entries=Files.list(directory)) { assertEquals(0,entries.count()); }
    }

    @Test
    void oldUnlockedLegacyFilesAreRemovedAndNewFilesKeepTheirBytes() throws Exception {
        Path old=write("finalize-123.tmp"),young=write("verification-456.tmp");
        old(old);
        var result=manager.sweep(directory,RETENTION,100);
        assertEquals(1,result.deleted());assertEquals(0,result.failed());
        assertFalse(Files.exists(old));assertFalse(Files.exists(sidecar(old)));
        assertEquals("private bytes",Files.readString(young));
    }

    @Test
    void matchingSymlinksDirectoriesAndForeignNamesAreNotCollected() throws Exception {
        Path target=directory.resolve("kept-source.bin");Files.writeString(target,"keep forever");old(target);
        Path link=directory.resolve("upload-123.part");Files.createSymbolicLink(link,target);
        Path folder=directory.resolve("finalize-456.tmp");Files.createDirectory(folder);old(folder);
        Path foreign=write("personal-data.tmp");old(foreign);
        Path malformed=write("verification-secret.tmp");old(malformed);
        var result=manager.sweep(directory,RETENTION,100);
        assertEquals(0,result.deleted());assertEquals(0,result.failed());
        assertTrue(Files.isSymbolicLink(link));assertTrue(Files.isDirectory(folder));
        assertEquals("keep forever",Files.readString(target));assertTrue(Files.exists(foreign));assertTrue(Files.exists(malformed));
    }

    @Test
    void aSymlinkLeaseCannotRedirectLockingOrDeletionOutsideTheDirectory() throws Exception {
        Path data=write("upload-123.part");old(data);
        Path target=write("keep.lock");old(target);Files.createSymbolicLink(sidecar(data),target);
        var result=manager.sweep(directory,RETENTION,100);
        assertEquals(0,result.deleted());assertEquals(0,result.failed());
        assertEquals("private bytes",Files.readString(data));assertEquals("private bytes",Files.readString(target));
        assertTrue(Files.isSymbolicLink(sidecar(data)));
    }

    @Test
    void crashedLeaseFilesCanBeRemovedButForeignLeaseFilesRemain() throws Exception {
        Path abandoned=write("verification-123.tmp.lock");old(abandoned);
        Path foreign=write("unmanaged.tmp.lock");old(foreign);
        var result=manager.sweep(directory,RETENTION,100);
        assertEquals(1,result.deleted());assertFalse(Files.exists(abandoned));assertTrue(Files.exists(foreign));
    }

    @Test
    void anActiveLeaseIsProtectedEvenIfItsDataWasRemovedByAnotherActor() throws Exception {
        try (var handle=manager.create(directory,"upload-",".part")) {
            Files.delete(handle.path());old(sidecar(handle.path()));
            var result=manager.sweep(directory,RETENTION,100);
            assertTrue(result.locked()>0);assertEquals(0,result.deleted());assertTrue(Files.exists(sidecar(handle.path())));
        }
    }

    @Test
    void closingAnOldHandleDoesNotDeleteAReplacementEntry() throws Exception {
        var handle=manager.create(directory,"upload-",".part");Path path=handle.path();
        Files.delete(path);Files.writeString(path,"replacement");
        handle.close();
        assertEquals("replacement",Files.readString(path));
    }

    @Test
    void directorySymlinksAreRejectedForCreationAndSweeping() throws Exception {
        Path actual=directory.resolve("actual");Files.createDirectory(actual);
        Path file=actual.resolve("upload-123.part");Files.writeString(file,"outside");old(file);
        Path link=directory.resolve("alias");Files.createSymbolicLink(link,actual);
        assertThrows(IOException.class,()->manager.create(link,"upload-",".part"));
        assertEquals(1,manager.sweep(link,RETENTION,100).failed());
        assertEquals("outside",Files.readString(file));
    }

    @Test
    void aSharedDirectoryCannotBecomeTheCleanupNamespace() throws Exception {
        Path shared=directory.resolve("shared");Files.createDirectory(shared);
        Path existing=shared.resolve("upload-123.part");Files.writeString(existing,"another application");old(existing);
        Files.setPosixFilePermissions(shared,PosixFilePermissions.fromString("rwxr-xr-x"));
        try {
            assertThrows(IOException.class,()->manager.create(shared,"upload-",".part"));
            assertEquals(1,manager.sweep(shared,RETENTION,100).failed());
            assertEquals("another application",Files.readString(existing));
        } finally { Files.setPosixFilePermissions(shared,PosixFilePermissions.fromString("rwx------")); }
    }

    @Test
    void scanLimitsBoundInspectedEntriesAndLeaveWorkForAnotherSweep() throws Exception {
        for (int i=0;i<8;i++) old(write("upload-"+i+".part"));
        var result=manager.sweep(directory,RETENTION,3);
        assertEquals(3,result.inspected());assertTrue(result.deleted()<=3);
        long remaining;try(var files=Files.list(directory)){remaining=files.count();}
        assertTrue(remaining>=5);
        manager.sweep(directory,RETENTION,100);
        try(var files=Files.list(directory)){assertEquals(0,files.count());}
    }

    @Test
    void twoConcurrentSweepersReservePathsBeforeOpeningSidecarDescriptors() throws Exception {
        for (int i=0;i<30;i++) old(write("upload-"+i+".part"));
        CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var left=workers.submit(()->{start.await();return manager.sweep(directory,RETENTION,1000);});
            var right=workers.submit(()->{start.await();return new TemporaryFileManager().sweep(directory,RETENTION,1000);});
            start.countDown();
            var a=left.get(5,TimeUnit.SECONDS);var b=right.get(5,TimeUnit.SECONDS);
            assertEquals(0,a.failed()+b.failed());
        }
        manager.sweep(directory,RETENTION,1000);
        try(var files=Files.list(directory)){assertEquals(0,files.count());}
    }

    @Test
    void failedCloseDefersFilesUntilDirectoryAccessRecovers() throws Exception {
        Path privateDirectory=directory.resolve("private");
        var handle=manager.create(privateDirectory,"upload-",".part");Path path=handle.path();old(path);
        Files.setPosixFilePermissions(privateDirectory,PosixFilePermissions.fromString("r-x------"));
        try { assertDoesNotThrow(handle::close); }
        finally { Files.setPosixFilePermissions(privateDirectory,PosixFilePermissions.fromString("rwx------")); }
        assertTrue(Files.exists(path));
        assertEquals(1,manager.sweep(privateDirectory,RETENTION,100).deleted());
        assertFalse(Files.exists(path));assertFalse(Files.exists(sidecar(path)));
    }

    @Test
    void absentAndInvalidDirectoriesHaveSafeCountsAndNoProviderText() throws Exception {
        assertEquals(new TemporaryFileManager.SweepResult(0,0,0,0,0),
                manager.sweep(directory.resolve("absent"),RETENTION,100));
        Path file=write("not-a-directory");
        var failed=manager.sweep(file,RETENTION,100);
        assertEquals(1,failed.failed());assertFalse(failed.toString().contains(file.toString()));
        assertTrue(Files.exists(file));
    }

    @Test
    void invalidLimitsAndUnmanagedCreationKindsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->manager.sweep(directory,Duration.ZERO,1));
        assertThrows(IllegalArgumentException.class,()->manager.sweep(directory,RETENTION,0));
        assertThrows(IllegalArgumentException.class,()->manager.sweep(directory,RETENTION,10001));
        assertThrows(IllegalArgumentException.class,()->manager.create(directory,"arbitrary-",".tmp"));
    }

    private Path write(String name) throws IOException {
        Path path=directory.resolve(name);Files.writeString(path,"private bytes");return path;
    }
    private static Path sidecar(Path path) { return path.resolveSibling(path.getFileName()+".lock"); }
    private static void old(Path path) throws IOException {
        Files.setLastModifiedTime(path,FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    }
}
