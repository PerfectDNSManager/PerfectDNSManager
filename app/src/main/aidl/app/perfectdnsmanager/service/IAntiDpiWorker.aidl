package app.perfectdnsmanager.service;
import android.os.ParcelFileDescriptor;
interface IAntiDpiWorker {
    boolean start(in ParcelFileDescriptor bridge, boolean ipv6);
    oneway void stop();
}
