// Read-only serial access mirroring h12_mavlink_heartbeat.py lines 97-108.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

typedef struct {
    int fd;
    int have_orig;
    struct termios orig;
} Port;

// Returns an opaque handle (> 0) or -errno.
JNIEXPORT jlong JNICALL
Java_com_mavheart_h12_SerialPort_nativeOpen(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -ENOMEM;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_NOCTTY);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -err;

    Port *p = calloc(1, sizeof(Port));
    if (!p) { close(fd); return -ENOMEM; }
    p->fd = fd;

    struct termios t;
    if (tcgetattr(fd, &t) != 0) { err = errno; close(fd); free(p); return -err; }
    p->orig = t;
    p->have_orig = 1;

    t.c_iflag = 0;
    t.c_oflag = 0;
    t.c_cflag &= ~(CSIZE | PARENB | CSTOPB | CRTSCTS);
    t.c_cflag |= CS8 | CREAD | CLOCAL;
    t.c_lflag = 0;
    t.c_cc[VMIN] = 0;
    t.c_cc[VTIME] = 0;
    cfsetispeed(&t, B921600);
    cfsetospeed(&t, B921600);
    if (tcsetattr(fd, TCSANOW, &t) != 0) { err = errno; close(fd); free(p); return -err; }
    return (jlong)(intptr_t)p;
}

// Returns bytes read (> 0), 0 on timeout/no data, or -errno.
JNIEXPORT jint JNICALL
Java_com_mavheart_h12_SerialPort_nativeRead(JNIEnv *env, jclass cls, jlong handle,
                                            jbyteArray buf, jint timeoutMs) {
    Port *p = (Port *)(intptr_t)handle;
    struct pollfd pfd = { .fd = p->fd, .events = POLLIN };
    int r = poll(&pfd, 1, timeoutMs);
    if (r < 0) return errno == EINTR ? 0 : -errno;
    if (r == 0) return 0;
    if (pfd.revents & (POLLERR | POLLNVAL)) return -EIO;
    if (!(pfd.revents & POLLIN)) return (pfd.revents & POLLHUP) ? -EIO : 0;

    jsize cap = (*env)->GetArrayLength(env, buf);
    unsigned char tmp[4096];
    size_t want = cap < (jsize)sizeof(tmp) ? (size_t)cap : sizeof(tmp);
    ssize_t n = read(p->fd, tmp, want);
    if (n < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -errno;
    if (n > 0) (*env)->SetByteArrayRegion(env, buf, 0, (jsize)n, (const jbyte *)tmp);
    return (jint)n;
}

// Restores the original termios, closes the fd, frees the handle.
JNIEXPORT void JNICALL
Java_com_mavheart_h12_SerialPort_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    Port *p = (Port *)(intptr_t)handle;
    if (!p) return;
    if (p->have_orig) tcsetattr(p->fd, TCSANOW, &p->orig);
    close(p->fd);
    free(p);
}

JNIEXPORT jstring JNICALL
Java_com_mavheart_h12_SerialPort_nativeStrerror(JNIEnv *env, jclass cls, jint err) {
    return (*env)->NewStringUTF(env, strerror(err));
}
