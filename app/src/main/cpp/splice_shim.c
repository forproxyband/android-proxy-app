// Kernel zero-copy bridge for the NATIVE engine's TCP fast path.
//
// JVM has no equivalent of Go's `io.Copy(*net.TCPConn, *net.TCPConn)`
// which drops into splice(2) under the hood. Without this shim the
// agent's TCP bridge does 2 userspace copies per direction (NIO +
// DirectByteBuffer is the best we get from java.nio alone). On
// gigabit links that becomes the CPU bottleneck.
//
// Splice on Linux requires a pipe intermediary — you cannot splice
// directly between two TCP sockets. The dance is:
//
//   src socket → splice → pipe → splice → dst socket
//
// Page references move through the kernel; no bytes hit userspace.
//
// This works on every Android kernel (splice has been in Linux since
// 2.6.17, every Android device ships a newer kernel). The Java side
// extracts the raw int fd from a SocketChannel via reflection (see
// SpliceShim.kt) — that part is the only platform-specific piece.

#define _GNU_SOURCE
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

// Per-iteration ceiling. splice() may return less (in practice
// limited by pipe buffer capacity, ~64 KiB default, expandable to
// ~1 MiB via fcntl F_SETPIPE_SZ — we don't bother resizing because
// real-world throughput is dominated by the destination socket's
// send buffer, not the pipe).
#define CHUNK_DEFAULT (1024 * 1024)

JNIEXPORT jlong JNICALL
Java_com_proxyagent_app_nativeagent_SpliceShim_spliceLoop(
    JNIEnv *env, jobject thiz, jint fdSrc, jint fdDst, jint chunkSize) {

    int pipefd[2];
    if (pipe(pipefd) < 0) {
        // Cannot even create a pipe — caller should fall back to NIO.
        return -1;
    }

    size_t chunk = (chunkSize > 0) ? (size_t)chunkSize : (size_t)CHUNK_DEFAULT;
    jlong total = 0;

    for (;;) {
        // Step 1: pull bytes from src socket into the kernel pipe.
        ssize_t n = splice((int)fdSrc, NULL,
                           pipefd[1], NULL,
                           chunk,
                           SPLICE_F_MOVE | SPLICE_F_MORE);
        if (n == 0) {
            // Clean EOF on src — peer FIN'd; nothing left to copy.
            break;
        }
        if (n < 0) {
            if (errno == EINTR) continue;
            if (total == 0) {
                // Nothing got through yet. Tell the caller "couldn't
                // start" so it can fall back to NIO without data loss.
                close(pipefd[0]);
                close(pipefd[1]);
                return -1;
            }
            // Some bytes already moved — fallback would lose data.
            // Bail out cleanly and return the partial count.
            break;
        }

        // Step 2: drain the pipe into the destination socket.
        ssize_t pending = n;
        while (pending > 0) {
            ssize_t k = splice(pipefd[0], NULL,
                               (int)fdDst, NULL,
                               (size_t)pending,
                               SPLICE_F_MOVE | SPLICE_F_MORE);
            if (k == 0) {
                // Destination shut down. Treat as EOF.
                goto done;
            }
            if (k < 0) {
                if (errno == EINTR) continue;
                goto done;
            }
            pending -= k;
        }

        total += n;
    }

done:
    close(pipefd[0]);
    close(pipefd[1]);
    return total;
}
