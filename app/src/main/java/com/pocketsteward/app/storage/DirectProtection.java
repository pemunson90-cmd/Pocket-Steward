package com.pocketsteward.app.storage;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.HashSet;

/** Fresh filesystem checks, independent of scan indexes and plan provenance. */
public final class DirectProtection {
    public static final String MARKER = "POCKETSTEWARD-DO-NOT-SORT.md";
    private static final int MAX_ENTRIES = 100_000;
    private DirectProtection() {}

    /** Null permits mutation of an existing source; all other values are user-visible refusal reasons. */
    public static String refusal(String storageRoot, String source) {
        try {
            RootAndRequested paths = paths(storageRoot, source);
            Path root = paths.root;
            Path target = paths.existingTarget();
            if (target.getFileName().toString().equals(MARKER))
                return "Protected: the no-sort marker requires deliberate unprotect review.";
            for (Path parent = target.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent()) {
                String reason = inspectDirectory(parent);
                if (reason != null) return reason;
            }
            ArrayDeque<Path> pending = new ArrayDeque<>();
            pending.add(target);
            HashSet<Path> visited = new HashSet<>();
            int entries = 0;
            while (!pending.isEmpty()) {
                Path node = pending.removeLast();
                if (++entries > MAX_ENTRIES) return "Protection unverifiable: subtree exceeds the bounded check; split the operation.";
                BasicFileAttributes attributes = Files.readAttributes(node, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) return "Protection unverifiable: subtree contains a symbolic link.";
                if (!attributes.isDirectory()) continue;
                if (!visited.add(node)) return "Protection unverifiable: repeated directory.";
                try (DirectoryStream<Path> children = Files.newDirectoryStream(node)) {
                    for (Path child : children) {
                        if (child.getFileName().toString().equals(MARKER)) return "Protected: a folder inside this operation contains " + MARKER;
                        if (pending.size() + entries >= MAX_ENTRIES) return "Protection unverifiable: subtree exceeds the bounded check; split the operation.";
                        pending.add(child);
                    }
                }
            }
            return null;
        } catch (IOException | SecurityException e) {
            return "Protection unverifiable: folder access changed or ancestry cannot be inspected.";
        }
    }

    /**
     * Null permits creating/moving a child at {@code destination}. The destination may not exist yet.
     * Only its real existing ancestry is inspected, so a marker in an unrelated sibling subtree does
     * not freeze the whole parent. This is the mutation-boundary check Undo uses before restoring a
     * file into its original folder.
     */
    public static String refusalDestination(String storageRoot, String destination) {
        try {
            RootAndRequested paths = paths(storageRoot, destination);
            Path requested = paths.requested;
            if (requested.getFileName().toString().equals(MARKER))
                return "Protected: the no-sort marker can only be created through deliberate protection controls.";
            Path parentRequested = requested.getParent();
            if (parentRequested == null || !parentRequested.startsWith(paths.suppliedRoot))
                return "Protection unverifiable: destination parent is outside the storage root.";
            Path parent = paths.existingFromRequested(parentRequested);
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                return "Protection unverifiable: destination parent is not an inspectable directory.";
            for (Path folder = parent; folder != null && folder.startsWith(paths.root); folder = folder.getParent()) {
                String reason = inspectDirectory(folder);
                if (reason != null) return reason;
            }
            return null;
        } catch (IOException | SecurityException e) {
            return "Protection unverifiable: destination ancestry changed or cannot be inspected.";
        }
    }

    private static RootAndRequested paths(String storageRoot, String requestedPath) throws IOException {
        Path suppliedRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        Path root = suppliedRoot.toRealPath();
        Path requested = Paths.get(requestedPath).toAbsolutePath().normalize();
        if (!requested.startsWith(suppliedRoot) || requested.equals(suppliedRoot))
            throw new IOException("Mutation must be below the storage root.");
        return new RootAndRequested(suppliedRoot, root, requested);
    }

    private static final class RootAndRequested {
        final Path suppliedRoot;
        final Path root;
        final Path requested;

        RootAndRequested(Path suppliedRoot, Path root, Path requested) {
            this.suppliedRoot = suppliedRoot;
            this.root = root;
            this.requested = requested;
        }

        Path existingTarget() throws IOException {
            Path target = existingFromRequested(requested);
            if (!target.equals(target.toRealPath()))
                throw new IOException("Source ancestry changed.");
            return target;
        }

        Path existingFromRequested(Path path) throws IOException {
            if (!path.startsWith(suppliedRoot)) throw new IOException("Path escaped storage root.");
            Path target = root.resolve(suppliedRoot.relativize(path));
            Path checked = root;
            for (Path part : root.relativize(target)) {
                checked = checked.resolve(part);
                if (Files.isSymbolicLink(checked)) throw new IOException("Symbolic link ancestry.");
            }
            Path real = target.toRealPath();
            if (!target.equals(real)) throw new IOException("Ancestry changed.");
            return target;
        }
    }

    private static String inspectDirectory(Path folder) throws IOException {
        // listFiles()==null must not become an empty folder or an allowed mutation.
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            int examined = 0;
            for (Path child : children) {
                if (++examined > MAX_ENTRIES) return "Protection unverifiable: ancestor listing exceeds bounded check.";
                if (child.getFileName().toString().equals(MARKER))
                    return "Protected: an ancestor contains " + MARKER;
            }
        }
        return null;
    }
}
