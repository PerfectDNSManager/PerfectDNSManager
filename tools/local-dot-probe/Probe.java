package app.perfectdnsmanager.localdotprobe;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import java.net.*;
import java.security.KeyStore;
import javax.net.ssl.*;
public final class Probe extends Instrumentation {
 public static void main(String[] args){
  System.out.println("shell uid="+Process.myUid());
  for(int port:new int[]{853,8853})try(ServerSocket s=new ServerSocket()) {s.bind(new InetSocketAddress("127.0.0.1",port));System.out.println("SHELL_BIND_OK "+port);}catch(Exception e){System.out.println("SHELL_BIND_FAIL "+port+" "+e);}
 }
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){
  StringBuilder s=new StringBuilder("uid="+Process.myUid()+" sdk="+android.os.Build.VERSION.SDK_INT+"\n");
  for(int port:new int[]{853,8853}) for(String host:new String[]{"127.0.0.1","::1"}){
   try(ServerSocket socket=new ServerSocket()){
    socket.bind(new InetSocketAddress(InetAddress.getByName(host),port));
    socket.setSoTimeout(2000);
    try(Socket client=new Socket()){client.connect(new InetSocketAddress(host,port),2000);try(Socket accepted=socket.accept()){s.append("BIND_CONNECT_OK "+host+":"+port+"\n");}}
   }catch(Exception e){s.append("BIND_CONNECT_FAIL "+host+":"+port+" "+e+"\n");}
  }
  try{
   java.security.cert.X509Certificate cert;
   try(java.io.InputStream in=getContext().getAssets().open("probe.cer")){cert=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);}
   TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init((KeyStore)null);
   for(TrustManager tm:tmf.getTrustManagers()) if(tm instanceof X509TrustManager){
    try{((X509TrustManager)tm).checkServerTrusted(new java.security.cert.X509Certificate[]{cert},"RSA");s.append("SELF_SIGNED_TRUSTED_UNEXPECTED\n");}
    catch(java.security.cert.CertificateException expected){s.append("SELF_SIGNED_REJECTED "+expected.getClass().getSimpleName()+"\n");}
   }
  }catch(Exception e){s.append("TRUST_PROBE_ERROR "+e+"\n");}
  Bundle out=new Bundle();out.putString("stream",s.toString());finish(-1,out);
 }
}
