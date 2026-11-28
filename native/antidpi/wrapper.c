#include <jni.h>
#include <stdatomic.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <dlfcn.h>
#include <unistd.h>
#include <stdlib.h>

static atomic_int listener_port;
extern int pdm_byedpi_main(int argc, char **argv);
void pdm_listener_ready(int fd) {
    struct sockaddr_in addr; socklen_t len=sizeof(addr);
    if (getsockname(fd,(struct sockaddr*)&addr,&len)==0)
        atomic_store(&listener_port,ntohs(addr.sin_port));
}
JNIEXPORT jint JNICALL Java_app_perfectdnsmanager_service_AntiDpiNative_proxy(JNIEnv *env,jobject self) {
    // No arbitrary command line, files, remote listener, TTL trick or TLS bypass.
    unsetenv("SS_LOCAL_PORT"); unsetenv("SS_PLUGIN_OPTIONS");
    char *argv[]={"pdm-byedpi","-i","127.0.0.1","-p","0","-c","64","-b","16384","-N","-s","1+s",NULL};
    return pdm_byedpi_main(sizeof(argv)/sizeof(argv[0])-1,argv);
}
JNIEXPORT jint JNICALL Java_app_perfectdnsmanager_service_AntiDpiNative_port(JNIEnv *env,jobject self) {
    return atomic_load(&listener_port);
}
JNIEXPORT jint JNICALL Java_app_perfectdnsmanager_service_AntiDpiNative_tunnel(JNIEnv *env,jobject self,jstring config,jint fd) {
    void *lib=dlopen("libhev-socks5-tunnel.so",RTLD_NOW|RTLD_LOCAL);
    if (!lib) return -1;
    int (*start)(const unsigned char*,unsigned int,int)=dlsym(lib,"hev_socks5_tunnel_main_from_str");
    if (!start) return -1;
    const char *text=(*env)->GetStringUTFChars(env,config,NULL);
    if (!text) return -1;
    int result=start((const unsigned char*)text,(*env)->GetStringUTFLength(env,config),fd);
    (*env)->ReleaseStringUTFChars(env,config,text);
    return result;
}
