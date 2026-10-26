package dev.roguelike.world;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

public final class ManagedWorldFiles {
  private final Path root;

  public ManagedWorldFiles(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path validate(String id, Path path) throws IOException {
    String canonical = UUID.fromString(id).toString();
    Path p = path.toAbsolutePath().normalize();
    if (!p.equals(root.resolve(canonical)) || p.equals(root))
      throw new IOException("Refusing unmanaged world path: " + p);
    for (Path part = p; part != null; part = part.getParent())
      if (Files.isSymbolicLink(part)) throw new IOException("Symlink in world path");
    return p;
  }

  public void delete(String id, Path folder) throws IOException {
    Path p = validate(id, folder);
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(
        p,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a)
              throws IOException {
            if (a.isSymbolicLink()) throw new IOException("Symlink directory");
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
            if (a.isSymbolicLink()) throw new IOException("Symlink file");
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) throw e;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
