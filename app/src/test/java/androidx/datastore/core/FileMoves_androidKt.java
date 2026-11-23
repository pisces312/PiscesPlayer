package androidx.datastore.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Test-only shadow of DataStore's internal {@code FileMoves.android.kt} top-level function.
 *
 * <p>The Android variant of {@code atomicMoveTo} branches on {@code Build.VERSION.SDK_INT}:
 * on API 26+ it delegates to {@code Api26Impl.move} ({@code Files.move} with
 * {@code REPLACE_EXISTING}), below that it falls back to {@code File.renameTo}. In JVM unit
 * tests {@code SDK_INT} is 0, so every DataStore write takes the legacy {@code renameTo}
 * path — which on Windows with a stock JDK (Temurin) fails when the destination file already
 * exists (POSIX rename replaces, and JetBrains Runtime patches {@code renameTo} to behave
 * that way, which is why the suite was green before the daemon JVM switched to JDK 25).
 *
 * <p>This class sits earlier on the test classpath than the library's copy and implements
 * the move with {@code Files.move(REPLACE_EXISTING)} — exactly the semantics production
 * gets on API 26+ — so sequential DataStore writes behave the same on every test JVM.
 * Production code is untouched: the real class ships inside the datastore AAR.
 */
public final class FileMoves_androidKt {

    private FileMoves_androidKt() {
    }

    public static boolean atomicMoveTo(File from, File to) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
