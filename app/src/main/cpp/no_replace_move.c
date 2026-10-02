#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* UTF-8 byte arrays avoid JNI's modified UTF-8 encoding of supplementary characters. */
JNIEXPORT jint JNICALL
Java_com_pocketsteward_app_storage_NoReplaceMove_renamePaths(JNIEnv *env, jclass type,
                                                           jbyteArray from, jbyteArray to) {
    (void)type;
    if (from == NULL || to == NULL) return EINVAL;
    jsize from_length = (*env)->GetArrayLength(env, from);
    jsize to_length = (*env)->GetArrayLength(env, to);
    if (from_length <= 0 || to_length <= 0) return EINVAL;
    if (from_length >= PATH_MAX || to_length >= PATH_MAX) return ENAMETOOLONG;
    char source[PATH_MAX], destination[PATH_MAX];
    (*env)->GetByteArrayRegion(env, from, 0, from_length, (jbyte *)source);
    if ((*env)->ExceptionCheck(env)) return EINVAL;
    (*env)->GetByteArrayRegion(env, to, 0, to_length, (jbyte *)destination);
    if ((*env)->ExceptionCheck(env)) return EINVAL;
    if (memchr(source, '\0', (size_t)from_length) || memchr(destination, '\0', (size_t)to_length)) return EINVAL;
    source[from_length] = '\0'; destination[to_length] = '\0';
#ifdef SYS_renameat2
    /* RENAME_NOREPLACE = 1. Unsupported kernels/filesystems refuse without changing either path. */
    if (syscall(SYS_renameat2, AT_FDCWD, source, AT_FDCWD, destination, 1U) == 0) return 0;
    return errno;
#else
    return ENOSYS;
#endif
}
