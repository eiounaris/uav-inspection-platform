package com.uav.inspection.common.temp;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Active files carry an OS lease on a separate inode, independent of SDK streams on the data file. */
@Component
@Profile("storage")
public class TemporaryFileManager implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(TemporaryFileManager.class);
    private static final Pattern NAME=Pattern.compile("(?:upload-[0-9]+\\.part|(?:finalize|verification)-[0-9]+\\.tmp)");
    // Opening and closing a second descriptor to an inode can release POSIX process locks.
    // Reserve both producers and sweepers before they open their sidecar descriptor.
    private static final Set<Path> RESERVED=ConcurrentHashMap.newKeySet();
    private static final int MAX_DIRECTORY_CURSORS=16;
    private final Clock clock;
    private final Map<Path,Cursor> cursors=new LinkedHashMap<>(16,0.75f,true);
    private volatile boolean stopped;

    public TemporaryFileManager() { this(Clock.systemUTC()); }
    TemporaryFileManager(Clock clock) { this.clock=clock; }

    public Handle create(Path directory,String prefix,String suffix) throws IOException {
        if (stopped) throw new IOException("Temporary file manager is stopped");
        if (!("upload-".equals(prefix) && ".part".equals(suffix)
                || Set.of("finalize-","verification-").contains(prefix) && ".tmp".equals(suffix)))
            throw new IllegalArgumentException("Unsupported managed temporary file kind");
        Path parent=privateDirectory(directory,true);
        Path data=Files.createTempFile(parent,prefix,suffix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Path sidecar=sidecar(data);
        FileChannel channel=null,dataChannel=null;
        boolean created=false;
        if (!RESERVED.add(data)) {
            Files.deleteIfExists(data);
            throw new IOException("Temporary file lease unavailable");
        }
        try {
            // Keeping this descriptor open prevents inode reuse if an entry is replaced before close.
            // It is not the lease inode; SDK streams can freely open and close the data file.
            dataChannel=FileChannel.open(data,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            channel=FileChannel.open(sidecar,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            created=true;
            FileLock lock=channel.tryLock();
            if (lock==null) throw new IOException("Temporary file lease unavailable");
            BasicFileAttributes attributes=attributes(sidecar),dataAttributes=attributes(data);
            if (attributes.fileKey()==null || dataAttributes.fileKey()==null)
                throw new IOException("Temporary file identity unavailable");
            return new Handle(data,sidecar,dataChannel,channel,lock,dataAttributes.fileKey(),attributes.fileKey());
        } catch (IOException | RuntimeException failure) {
            closeChannel(channel);
            closeChannel(dataChannel);
            deleteOwned(data);
            if (created) deleteOwned(sidecar);
            RESERVED.remove(data);
            throw new IOException("Temporary file lease unavailable");
        }
    }

    /** Counts candidates only; names, paths and provider details never become cleanup output. */
    public synchronized SweepResult sweep(Path directory,Duration retention,int limit) {
        if (retention==null || retention.isNegative() || retention.isZero() || limit<1 || limit>10000)
            throw new IllegalArgumentException("Invalid temporary cleanup limits");
        Counters counts=new Counters();
        if (stopped) { counts.failed++; return counts.result(); }
        Instant cutoff=clock.instant().minus(retention);
        Path parent=directory.toAbsolutePath().normalize();
        try {
            privateDirectory(parent,false);
            Object key=attributes(parent).fileKey();
            if (key==null) throw new IOException("Temporary directory identity unavailable");
            Cursor cursor=cursors.get(parent);
            if (cursor!=null && !key.equals(cursor.directoryKey)) { releaseCursor(parent); cursor=null; }
            if (cursor==null) {
                cursor=openCursor(parent,key);
                if (cursors.size()>=MAX_DIRECTORY_CURSORS) releaseCursor(cursors.keySet().iterator().next());
                cursors.put(parent,cursor);
            }
            // Retain traversal position between batches, including batches of active or foreign entries.
            while (counts.inspected<limit && cursor.entries.hasNext()) {
                Path entry=cursor.entries.next();
                counts.inspected++;
                String name=entry.getFileName().toString();
                if (NAME.matcher(name).matches()) sweepData(cursor.directory,parent,entry.getFileName(),cutoff,counts);
                else if (name.endsWith(".lock") && NAME.matcher(name.substring(0,name.length()-5)).matches())
                    sweepOrphanLease(cursor.directory,parent,entry.getFileName(),cutoff,counts);
                else counts.skipped++;
            }
            if (!cursor.entries.hasNext()) releaseCursor(parent);
        } catch (NoSuchFileException absent) {
            releaseCursor(parent);
            // No private temporary directory means there are no leftovers to collect.
        } catch (IOException | RuntimeException unavailable) {
            releaseCursor(parent);
            counts.failed++;
            LOG.warn("Temporary sweep deferred; filesystem access unavailable");
        }
        return counts.result();
    }

    // opened and secure refer to the same stream. Cursor owns it on success;
    // releaseCursor closes it after traversal, replacement, eviction or shutdown.
    // The catch below closes it if construction fails.
    @SuppressWarnings("resource")
    private static Cursor openCursor(Path parent,Object key) throws IOException {
        DirectoryStream<Path> opened=Files.newDirectoryStream(parent);
        try {
            if (!(opened instanceof SecureDirectoryStream<Path> secure)
                    || !key.equals(secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))
                throw new IOException("Anchored temporary directory unavailable");
            return new Cursor(secure,secure.iterator(),key);
        } catch (IOException | RuntimeException failure) {
            opened.close();
            throw failure;
        }
    }

    private void releaseCursor(Path parent) {
        Cursor cursor=cursors.remove(parent);
        if (cursor!=null) try { cursor.directory.close(); }
        catch (IOException unavailable) { LOG.warn("Temporary directory scan close deferred"); }
    }

    /** Closing scans does not release any producer's active sidecar lease. */
    @Override @PreDestroy public synchronized void close() {
        if (stopped) return;
        stopped=true;
        for (Path parent:java.util.List.copyOf(cursors.keySet())) releaseCursor(parent);
    }

    private record Cursor(SecureDirectoryStream<Path> directory,Iterator<Path> entries,Object directoryKey) {}

    private static void sweepData(SecureDirectoryStream<Path> directory,Path parent,Path name,
                                  Instant cutoff,Counters counts) {
        Path data=parent.resolve(name);
        if (!RESERVED.add(data)) { counts.locked++; return; }
        Path leaseName=Path.of(name+".lock");
        try {
            BasicFileAttributes before=attributes(directory,name);
            if (!eligible(before,cutoff)) { counts.skipped++; return; }
            if (exists(directory,leaseName) && !attributes(directory,leaseName).isRegularFile()) {
                counts.skipped++; return;
            }
            try (FileChannel channel=FileChannel.open(parent.resolve(leaseName),Set.of(StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                FileLock lock=tryLock(channel);
                if (lock==null) { counts.locked++; return; }
                try (lock) {
                    BasicFileAttributes lease=attributes(directory,leaseName);
                    BasicFileAttributes current=attributes(directory,name);
                    if (!sameFile(before,current) || !eligible(current,cutoff)) { counts.skipped++; return; }
                    directory.deleteFile(name);
                    counts.deleted++;
                    deleteLease(directory,leaseName,lease.fileKey());
                }
            }
        } catch (NoSuchFileException disappeared) {
            counts.skipped++;
        } catch (IOException | RuntimeException unavailable) {
            counts.failed++;
            LOG.warn("Temporary file collection deferred; filesystem access unavailable");
        } finally { RESERVED.remove(data); }
    }

    private static void sweepOrphanLease(SecureDirectoryStream<Path> directory,Path parent,Path name,
                                         Instant cutoff,Counters counts) {
        String value=name.toString();
        Path dataName=Path.of(value.substring(0,value.length()-5));
        Path data=parent.resolve(dataName);
        if (!RESERVED.add(data)) { counts.locked++; return; }
        try {
            if (exists(directory,dataName)) { counts.skipped++; return; }
            BasicFileAttributes before=attributes(directory,name);
            if (!eligible(before,cutoff)) { counts.skipped++; return; }
            try (FileChannel channel=FileChannel.open(parent.resolve(name),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock=tryLock(channel);
                if (lock==null) { counts.locked++; return; }
                try (lock) {
                    if (exists(directory,dataName) || !sameFile(before,attributes(directory,name))) { counts.skipped++; return; }
                    directory.deleteFile(name);
                    counts.deleted++;
                }
            }
        } catch (NoSuchFileException disappeared) {
            counts.skipped++;
        } catch (IOException | RuntimeException unavailable) {
            counts.failed++;
            LOG.warn("Temporary lease collection deferred; filesystem access unavailable");
        } finally { RESERVED.remove(data); }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException busy) { return null; }
    }

    private static boolean eligible(BasicFileAttributes attributes,Instant cutoff) {
        return attributes.isRegularFile() && attributes.fileKey()!=null
                && !attributes.lastModifiedTime().toInstant().isAfter(cutoff);
    }

    private static boolean sameFile(BasicFileAttributes first,BasicFileAttributes second) {
        return first.fileKey()!=null && first.fileKey().equals(second.fileKey()) && second.isRegularFile()
                && first.size()==second.size() && first.lastModifiedTime().equals(second.lastModifiedTime())
                && first.creationTime().equals(second.creationTime());
    }

    private static boolean exists(SecureDirectoryStream<Path> directory,Path name) throws IOException {
        try { attributes(directory,name); return true; }
        catch (NoSuchFileException absent) { return false; }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory,Path name) throws IOException {
        return directory.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
    }

    private static void deleteLease(SecureDirectoryStream<Path> directory,Path name,Object key) throws IOException {
        if (!exists(directory,name)) return;
        BasicFileAttributes current=attributes(directory,name);
        if (key!=null && key.equals(current.fileKey()) && current.isRegularFile()) directory.deleteFile(name);
    }

    private static Path privateDirectory(Path supplied,boolean create) throws IOException {
        Path parent=supplied.toAbsolutePath().normalize();
        Path item=parent.getRoot();
        for (Path segment:parent) {
            item=item.resolve(segment);
            if (Files.isSymbolicLink(item)) throw new IOException("Private temporary directory is unavailable");
        }
        if (create) Files.createDirectories(parent,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!attributes(parent).isDirectory()) throw new IOException("Private temporary directory is unavailable");
        var access=Files.readAttributes(parent,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        var owner=parent.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        if (!access.owner().equals(owner) || access.permissions().stream().anyMatch(permission->
                !Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE).contains(permission)))
            throw new IOException("Private temporary directory is unavailable");
        return parent;
    }

    private static Path sidecar(Path data) { return data.resolveSibling(data.getFileName()+".lock"); }
    private static void closeChannel(FileChannel channel) {
        if (channel!=null) try { channel.close(); } catch (IOException ignored) { }
    }
    private static void deleteOwned(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException | RuntimeException unavailable) { LOG.warn("Temporary cleanup deferred; filesystem access unavailable"); }
    }

    public static final class Handle implements AutoCloseable {
        private final Path path,sidecar;
        private final FileChannel dataChannel,channel;
        private final FileLock lock;
        private final Object dataKey,sidecarKey;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Handle(Path path,Path sidecar,FileChannel dataChannel,FileChannel channel,FileLock lock,Object dataKey,Object sidecarKey) {
            this.path=path;this.sidecar=sidecar;this.dataChannel=dataChannel;this.channel=channel;this.lock=lock;
            this.dataKey=dataKey;this.sidecarKey=sidecarKey;
        }
        public Path path() { return path; }
        @Override public void close() {
            if (!closed.compareAndSet(false,true)) return;
            try {
                try {
                    BasicFileAttributes current=attributes(path);
                    if (dataKey.equals(current.fileKey()) && current.isRegularFile()) deleteOwned(path);
                } catch (IOException ignored) { }
                // The data is removed before its lease can be released or replaced.
                try {
                    BasicFileAttributes current=attributes(sidecar);
                    if (sidecarKey!=null && sidecarKey.equals(current.fileKey()) && current.isRegularFile()) deleteOwned(sidecar);
                } catch (IOException ignored) { }
            } finally {
                try { lock.release(); } catch (IOException ignored) { }
                closeChannel(channel);
                closeChannel(dataChannel);
                RESERVED.remove(path);
            }
        }
    }

    public record SweepResult(int inspected,int deleted,int locked,int skipped,int failed) {}
    private static final class Counters {
        int inspected,deleted,locked,skipped,failed;
        SweepResult result() { return new SweepResult(inspected,deleted,locked,skipped,failed); }
    }
}
