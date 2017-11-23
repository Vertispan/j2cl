package com.vertispan.j2cl;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Listen to changes to the File System using the {@link WatchService} of
 * {@link FileSystems#getDefault()}. Can be used to get changes to the file system
 * after an event. To get changes, you need to perform a collection. The collections
 * avaliable are:<br>
 * <br>
 * {@link ResourceListener#collectEvents(boolean includeDir)} - collect all events that occurred after
 * the last collection (including directories iff {@code includeDir} is true). Non-blocking<br>
 * <br>
 * {@link ResourceListener#blockAndCollectEvents(boolean includeDir)} - collect all events that occurred after
 * the last collection (including directories iff {@code includeDir} is true). Blocks until an event
 * is available<br>
 * <br>
 * {@link ResourceListener#mockCollection()} - Do a mock collection, causing all events that occurred after
 * the last collection to be lost.
 */
public class ResourceListener {

    HashMap<WatchKey, Path> keyPathMap;
    WatchService watcher;

    Set<Path> modifiedFiles;
    Set<Path> createdFiles;
    Set<Path> deletedFiles;

    public ResourceListener() throws IOException {
        watcher = FileSystems.getDefault().newWatchService();
        keyPathMap = new HashMap<>();
        modifiedFiles = new HashSet<>();
        createdFiles = new HashSet<>();
        deletedFiles = new HashSet<>();
    }

    /**
     * Clears the modified, created and deleted files
     */
    public void resetEvents() {
        modifiedFiles.clear();
        createdFiles.clear();
        deletedFiles.clear();
    }

    /**
     * Collects all the events that happen since this was last collection
     * (or since this ResourceListener was created for the first one)
     * @param includeCreateDirectories If true, create directories events are also
     * added; otherwise create directories events are not added
     * @throws IOException
     */
    public void collectEvents(boolean includeCreateDirectories) throws IOException {
        WatchKey queuedKey;
        while ((queuedKey = watcher.poll()) != null) {
            for (WatchEvent<?> watchEvent : queuedKey.pollEvents()) {
                Path path = (Path) watchEvent.context();//this is a relative path
                Path parentPath = keyPathMap.get(queuedKey);
                path = parentPath.resolve(path);

                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerChangeListener(path);
                    if (includeCreateDirectories || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        createdFiles.add(path);
                    }
                }
                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    modifiedFiles.add(path);
                }
                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    deletedFiles.add(path);
                }
            }

            if (!queuedKey.reset()) {
                keyPathMap.remove(queuedKey);
            }
            if (keyPathMap.isEmpty()) {
                break;
            }
        }
    }

    /**
     * Block until a event can be collected and then perform a collection
     * (or since this ResourceListener was created for the first one)
     * @param includeCreateDirectories If true, create directories events are also
     * added; otherwise create directories events are not added
     * @throws IOException
     * @throws InterruptedException 
     */
    public void blockAndCollectEvents(boolean includeCreateDirectories) throws IOException, InterruptedException {
        WatchKey queuedKey = watcher.take();
        do {
            for (WatchEvent<?> watchEvent : queuedKey.pollEvents()) {
                Path path = (Path) watchEvent.context();//this is a relative path
                Path parentPath = keyPathMap.get(queuedKey);
                path = parentPath.resolve(path);

                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerChangeListener(path);
                    if (includeCreateDirectories || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        createdFiles.add(path);
                    }
                }
                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    modifiedFiles.add(path);
                }
                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    deletedFiles.add(path);
                }
            }

            if (!queuedKey.reset()) {
                keyPathMap.remove(queuedKey);
            }
            if (keyPathMap.isEmpty()) {
                break;
            }
        } while ((queuedKey = watcher.poll()) != null);
    }

    /**
     * Does a mock collection, causing all uncollected events before this call to be lost
     * @throws IOException
     */
    public void mockCollection() throws IOException {
        WatchKey queuedKey;
        while ((queuedKey = watcher.poll()) != null) {
            for (WatchEvent<?> watchEvent : queuedKey.pollEvents()) {
                Path path = (Path) watchEvent.context();//this is a relative path
                Path parentPath = keyPathMap.get(queuedKey);
                path = parentPath.resolve(path);

                if (watchEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerChangeListener(path);
                }
            }

            if (!queuedKey.reset()) {
                keyPathMap.remove(queuedKey);
            }
            if (keyPathMap.isEmpty()) {
                break;
            }
        }
    }

    public Set<Path> getCreatedFiles() {
        return Collections.unmodifiableSet(createdFiles);
    }

    public Set<Path> getModifiedFiles() {
        return Collections.unmodifiableSet(modifiedFiles);
    }

    public Set<Path> getDeletedFiles() {
        return Collections.unmodifiableSet(deletedFiles);
    }

    /**
     * Listen to path and all of its descendant directories
     * @param path Path to listen to
     * @throws IOException
     */
    public void registerChangeListener(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        WatchKey key;
        key = path.register(watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyPathMap.put(key, path);

        for (File f : path.toFile().listFiles()) {
            registerChangeListener(f.toPath());
        }
    }
}
