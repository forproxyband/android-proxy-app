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
// On Android targeting API 28+ ("non-SDK API restrictions"), both
// reflective AND JNI access to hidden fields is enforced — apps
// targeting API 30+ get a hard block, returning NULL from
// GetFieldID / GetMethodID instead of finding the member. The
// historical JNI escape hatch was closed in Android 9+.
//
// To still extract the int fd we run several strategies in order,
// returning the first one that works. Each strategy targets a
// different platform/version combination:
//
//   1. SocketChannelImpl.fdVal (int) — present on Android since
//      libcore was ported from OpenJDK 11+. Sometimes accessible
//      because it's a private int, not a reference to a system class.
//
//   2. SocketChannelImpl.fd (FileDescriptor) + FileDescriptor.fd
//      (int) — the canonical OpenJDK / current AOSP layout.
//
//   3. Same FileDescriptor, but with field "descriptor" (older AOSP
//      libcore convention used pre-Android 10).
//
//   4. Same FileDescriptor, calling getInt$() method (Android's
//      @hide accessor — was widely used before the blocklist).
//
// On every strategy we clear any pending JNI exception (GetFieldID
// throws NoSuchFieldError on miss + a SecurityException when the
// hidden API blocks access; both must be cleared before the next
// attempt or the JVM will abort the JNI call).
//
// Return value (jlong, packed):
//   Non-negative: success. Decode as:
//                 fd       = (int)(packed & 0xFFFFFFFFL)
//                 strategy = (int)((packed >> 32) & 0xFF)
//                            1 = SocketChannelImpl.fdVal
//                            2 = FileDescriptor.fd
//                            3 = FileDescriptor.descriptor
//                            4 = FileDescriptor.getInt$()
//   Negative:     error code (cast through Long.toInt for legibility):
//   -1:   channel null / unrecognised layout
//   -2:   no "fd" field of type FileDescriptor anywhere in hierarchy
//         (would indicate a totally different SocketChannel impl)
//   -3:   fd field exists but its FileDescriptor object is null
//         (channel not connected?)
//   -4:   FileDescriptor's int member couldn't be extracted by ANY
//         strategy — hidden API enforcement fully locked
//   -10:  successfully read a field but value was < 0 (closed fd)
//
// Caller (SpliceShim) maps codes to diagnostic strings + the strategy
// ID to a human name, then logs both in a single line so the user
// can see exactly which path works (or that none does).
//
// Packing the strategy into the return saves a second JNI round-trip
// (no separate "which strategy worked" probe call) — it's free
// metadata for every successful extraction.

// Helper: walks the class hierarchy of `obj` looking for a field
// named `name` with JNI type signature `sig`. Returns a usable
// fieldID or NULL. The class chain is walked because GetFieldID
// only looks at the class it was given, not its superclasses.
static jfieldID find_field_in_hierarchy(JNIEnv *env, jobject obj,
                                        const char *name, const char *sig) {
    jclass cls = (*env)->GetObjectClass(env, obj);
    while (cls != NULL) {
        jfieldID f = (*env)->GetFieldID(env, cls, name, sig);
        if (f != NULL) {
            (*env)->DeleteLocalRef(env, cls);
            return f;
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        jclass parent = (*env)->GetSuperclass(env, cls);
        (*env)->DeleteLocalRef(env, cls);
        cls = parent;
    }
    return NULL;
}

// Helper: pack a strategy id and fd into a single jlong for the
// return value of extractFd. See the function header for layout.
static inline jlong pack(jint strategy, jint fd) {
    return ((jlong)strategy << 32) | ((jlong)fd & 0xFFFFFFFFLL);
}

JNIEXPORT jlong JNICALL
Java_com_proxyagent_app_nativeagent_SpliceShim_extractFd(
    JNIEnv *env, jobject thiz, jobject channel) {

    (void)thiz;
    if (channel == NULL) return -1;

    // Strategy 1: SocketChannelImpl.fdVal (int) — direct shortcut
    // when the field exists and isn't on the blocklist.
    jfieldID fdValField = find_field_in_hierarchy(env, channel, "fdVal", "I");
    if (fdValField != NULL) {
        jint v = (*env)->GetIntField(env, channel, fdValField);
        if (v >= 0) return pack(1, v);
        return -10;
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    // Strategy 2/3/4: get the FileDescriptor first, then try to read
    // its int member via several field/method variants.
    jfieldID fdField = find_field_in_hierarchy(env, channel,
                                                "fd", "Ljava/io/FileDescriptor;");
    if (fdField == NULL) return -2;

    jobject fdObj = (*env)->GetObjectField(env, channel, fdField);
    if (fdObj == NULL) return -3;

    jclass fdCls = (*env)->GetObjectClass(env, fdObj);

    // 2: OpenJDK / current AOSP — `private int fd`
    jfieldID intField = (*env)->GetFieldID(env, fdCls, "fd", "I");
    if (intField != NULL) {
        jint v = (*env)->GetIntField(env, fdObj, intField);
        (*env)->DeleteLocalRef(env, fdCls);
        (*env)->DeleteLocalRef(env, fdObj);
        return v < 0 ? -10 : pack(2, v);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    // 3: older AOSP libcore — `private int descriptor`
    intField = (*env)->GetFieldID(env, fdCls, "descriptor", "I");
    if (intField != NULL) {
        jint v = (*env)->GetIntField(env, fdObj, intField);
        (*env)->DeleteLocalRef(env, fdCls);
        (*env)->DeleteLocalRef(env, fdObj);
        return v < 0 ? -10 : pack(3, v);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    // 4: Android @hide accessor method `public int getInt$()`
    jmethodID getIntM = (*env)->GetMethodID(env, fdCls, "getInt$", "()I");
    if (getIntM != NULL) {
        jint v = (*env)->CallIntMethod(env, fdObj, getIntM);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        } else {
            (*env)->DeleteLocalRef(env, fdCls);
            (*env)->DeleteLocalRef(env, fdObj);
            return v < 0 ? -10 : pack(4, v);
        }
    } else {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, fdCls);
    (*env)->DeleteLocalRef(env, fdObj);
    return -4;  // hidden API fully locked, no extraction path worked
}
