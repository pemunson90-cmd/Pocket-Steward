#!/usr/bin/env python3
"""Compile and exercise the production JNI no-replace primitive on a Linux host."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
import com.pocketsteward.app.storage.NoReplaceMove;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
public final class MoveProbe {
  static void require(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    File a = root.resolve("資料-🦇.txt").toFile(), b = root.resolve("原稿-✨.txt").toFile();
    Files.writeString(a.toPath(), "source");
    if (args.length > 1) {
      try { NoReplaceMove.move(a, b); throw new AssertionError("Missing library was not refused"); }
      catch (IOException expected) { require(a.isFile() && !b.exists()); }
      System.out.println("Missing native library refuses safely."); return;
    }
    require(NoReplaceMove.move(a,b) == 0); require(!a.exists()); require(Files.readString(b.toPath()).equals("source"));
    Files.writeString(a.toPath(), "other");
    require(NoReplaceMove.move(a,b) != 0); require(Files.readString(a.toPath()).equals("other")); require(Files.readString(b.toPath()).equals("source"));
    java.lang.reflect.Method nativeMove = NoReplaceMove.class.getDeclaredMethod("renamePaths", byte[].class, byte[].class);
    nativeMove.setAccessible(true);
    for(byte[] invalid: new byte[][] {new byte[0], new byte[] {0}, new byte[8192]}) {
      require(((Integer)nativeMove.invoke(null, invalid, b.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8))) != 0);
      require(Files.readString(a.toPath()).equals("other")); require(Files.readString(b.toPath()).equals("source"));
    }
    Path dir = Files.createDirectory(root.resolve("folder")); Files.writeString(dir.resolve("keep.txt"), "keep");
    File destDir = root.resolve("folder-new").toFile();
    require(NoReplaceMove.move(dir.toFile(), destDir) == 0); require(Files.readString(destDir.toPath().resolve("keep.txt")).equals("keep"));
    Files.createDirectory(dir); require(NoReplaceMove.move(dir.toFile(), destDir) != 0);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for(int i=0;i<200;i++) {
        File source=root.resolve("s"+i).toFile(), dest=root.resolve("d"+i).toFile(); Files.writeString(source.toPath(),"approved");
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> move = pool.submit(() -> { start.await(); return NoReplaceMove.move(source,dest); });
        Future<Boolean> external = pool.submit(() -> { start.await(); try { Files.writeString(dest.toPath(),"external",StandardOpenOption.CREATE_NEW); return true; } catch(FileAlreadyExistsException expected) { return false; } });
        start.countDown(); int result=move.get(5,TimeUnit.SECONDS); boolean created=external.get(5,TimeUnit.SECONDS);
        if(created) { require(result != 0 && source.exists()); require(Files.readString(dest.toPath()).equals("external")); }
        else { require(result == 0 && !source.exists()); require(Files.readString(dest.toPath()).equals("approved")); }
      }
    } finally { pool.shutdownNow(); }
    System.out.println("Production JNI passed file/directory collisions, Unicode, and 200 external-create races.");
  }
}
'''

def main():
    java_home = Path(os.environ.get('JAVA_HOME') or Path(shutil.which('javac')).resolve().parent.parent)
    with tempfile.TemporaryDirectory(prefix='pocket-native-check-') as temporary:
        work = Path(temporary)
        (work / 'MoveProbe.java').write_text(JAVA)
        subprocess.run(['cc', '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror',
                        '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
                        str(ROOT / 'app/src/main/cpp/no_replace_move.c'), '-o', str(work / 'libpocketsteward_fs.so')], check=True)
        subprocess.run([str(java_home / 'bin/javac'), '-d', str(work),
                        str(ROOT / 'app/src/main/java/com/pocketsteward/app/storage/NoReplaceMove.java'), str(work / 'MoveProbe.java')], check=True)
        subprocess.run([str(java_home / 'bin/java'), '-Djava.library.path=' + str(work), '-cp', str(work), 'MoveProbe', str(work)], check=True)
        missing = work / 'missing'; missing.mkdir()
        subprocess.run([str(java_home / 'bin/java'), '-Djava.library.path=' + str(missing), '-cp', str(work), 'MoveProbe', str(missing), 'missing'], check=True)

if __name__ == '__main__':
    main()
