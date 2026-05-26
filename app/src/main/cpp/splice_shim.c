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
#include <string.h>

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

// Extract the raw POSIX file descriptor from a Java SocketChannel.
//
// Why this lives in JNI and not in Kotlin reflection:
//
// On Android targeting API 28+ ("non-SDK API restrictions"), reflective
// access to `sun.nio.ch.SocketChannelImpl.fd` and `FileDescriptor.fd` /
// `FileDescriptor.getInt$()` is restricted. Apps targeting API 30+
// hit a hard block (LinkageError / NoSuchMethodException) on these
// for any app that's not part of the platform.
//
// JNI's GetFieldID / GetIntField run at the JVM level and are not
// subject to the Java-reflection-layer non-SDK enforcement — they
// can read private fields of system classes the same way they read
// fields of user classes. This is the documented escape hatch
// (see Google's "Restrictions on non-SDK interfaces" doc, "JNI
// access" exclusion).
//
// We walk the channel's class hierarchy looking for a field named
// "fd" of type `Ljava/io/FileDescriptor;` — sun.nio.ch.SocketChannelImpl
// has it on every Android version we care about. Then we dereference
// the FileDescriptor's int member — tries "descriptor" first (the
// AOSP libcore convention) then "fd" (OpenJDK convention) for forward/
// backward compat.
//
// Returns the raw int fd on success, or -1 if any reflection step
// failed. Caller (SpliceShim.fdOf) treats -1 as "fall back to NIO".
JNIEXPORT jint JNICALL
Java_com_proxyagent_app_nativeagent_SpliceShim_extractFd(
    JNIEnv *env, jobject thiz, jobject channel) {

    (void)thiz;
    if (channel == NULL) return -1;

    // Find the FileDescriptor-typed "fd" field by walking up
    // the class hierarchy. SocketChannelImpl declares it directly.
    jclass searchCls = (*env)->GetObjectClass(env, channel);
    jfieldID fdField = NULL;

    while (searchCls != NULL) {
        fdField = (*env)->GetFieldID(env, searchCls,
                                     "fd", "Ljava/io/FileDescriptor;");
        if (fdField != NULL) break;
        // GetFieldID throws NoSuchFieldError on miss; clear it.
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        jclass parent = (*env)->GetSuperclass(env, searchCls);
        (*env)->DeleteLocalRef(env, searchCls);
        searchCls = parent;
    }
    if (fdField == NULL) {
        if (searchCls != NULL) (*env)->DeleteLocalRef(env, searchCls);
        return -1;
    }

    jobject fdObj = (*env)->GetObjectField(env, channel, fdField);
    (*env)->DeleteLocalRef(env, searchCls);
    if (fdObj == NULL) return -1;

    // Now extract the int from FileDescriptor. AOSP libcore names the
    // field "descriptor"; OpenJDK names it "fd". Try both.
    jclass fdCls = (*env)->GetObjectClass(env, fdObj);
    jfieldID intField = (*env)->GetFieldID(env, fdCls, "descriptor", "I");
    if (intField == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        intField = (*env)->GetFieldID(env, fdCls, "fd", "I");
    }
    (*env)->DeleteLocalRef(env, fdCls);
    if (intField == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, fdObj);
        return -1;
    }

    jint result = (*env)->GetIntField(env, fdObj, intField);
    (*env)->DeleteLocalRef(env, fdObj);
    return result < 0 ? -1 : result;
}
