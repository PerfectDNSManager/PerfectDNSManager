package app.perfectdnsmanager.antidpiprobe;
import android.app.Instrumentation;
import android.os.Bundle;
import android.net.SSLCertificateSocketFactory;
import java.net.*;
import java.io.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import java.util.Arrays;

/** Independent UID: unlike PDM's engines, this app must traverse the full VPN. */
public class Probe extends Instrumentation {
    Bundle args;
    public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public void onStart(){Bundle out=new Bundle();try{
        startActivitySync(new android.content.Intent(getTargetContext(),ProbeActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        android.os.SystemClock.sleep(500); // Let Android apply foreground UID policy.
        if(!"true".equals(args.getString("dnsOnly"))) {
            try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("10.0.2.2",18080),15000);http(socket);}
            KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
            try(InputStream in=getContext().getAssets().open("fixture.crt")){store.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(in));}
            TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            SSLCertificateSocketFactory factory=(SSLCertificateSocketFactory)SSLCertificateSocketFactory.getDefault(15000);
            factory.setTrustManagers(trust.getTrustManagers());
            try(SSLSocket socket=(SSLSocket)factory.createSocket()) {
                socket.connect(new InetSocketAddress("10.0.2.2",18443),15000);
                factory.setHostname(socket,"pdm-antidpi.test");socket.setSoTimeout(15000);socket.startHandshake();
                check(socket.getSession().getPeerCertificates().length>0,"TLS peer certificate");http(socket);
            }
            byte[] data="PDM UDP echo fixture".getBytes("UTF-8");
            try(DatagramSocket socket=new DatagramSocket()) {
                socket.setSoTimeout(15000);socket.send(new DatagramPacket(data,data.length,InetAddress.getByName("10.0.2.2"),18081));
                byte[] response=new byte[512];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);
                check(Arrays.equals(data,Arrays.copyOf(response,packet.getLength())),"UDP echo");
            }
        }
        dns(1);dns(28);
        if("true".equals(args.getString("ipv6Fixture"))) {
            boolean blocked="true".equals(args.getString("ipv6Blocked"));
            try(Socket socket=new Socket()) {
                socket.connect(new InetSocketAddress("fec0::2",18082),blocked?3000:15000);
                check(!blocked,"IPv6 must be blocked only when explicitly requested");http(socket);
            } catch(SocketTimeoutException e) {if(!blocked)throw e;}
        }
        out.putString("stream","ALL PASSED: independent UID DNS A/AAAA, "+("true".equals(args.getString("dnsOnly"))?"prefilter":"TCP, TLS with SNI and UDP")+"\n");finish(-1,out);
    }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));finish(1,out);}}
    void http(Socket socket)throws Exception {
        socket.setSoTimeout(15000);socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: pdm-antidpi.test\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;
        while((n=socket.getInputStream().read(b))!=-1){out.write(b,0,n);check(out.size()<200000,"HTTP bound");}
        String response=out.toString("US-ASCII");check(response.startsWith("HTTP/1.1 200")&&response.endsWith("PDM-ANTI-DPI-OK"),"HTTP fixture response");
    }
    void dns(int type)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{0x12,0x35,1,0,0,1,0,0,0,0,0,0});
        for(String part:"example.com".split("\\.")){out.write(part.length());out.write(part.getBytes("US-ASCII"));}
        out.write(new byte[]{0,0,(byte)type,0,1});byte[] query=out.toByteArray();
        try(DatagramSocket s=new DatagramSocket()){
            s.setSoTimeout(25000);s.send(new DatagramPacket(query,query.length,InetAddress.getByName("192.0.2.2"),53));
            byte[] b=new byte[8192];DatagramPacket p=new DatagramPacket(b,b.length);s.receive(p);
            int rcode=Integer.parseInt(args.getString("rcode","0"));
            check(p.getLength()>12&&b[0]==0x12&&b[1]==0x35&&(b[3]&15)==rcode,"DNS response type "+type+" expected rcode="+rcode+" actual="+(b[3]&15)+" length="+p.getLength());
            if(rcode==0)check((b[6]&255)*256+(b[7]&255)>0,"DNS answer type "+type);
            return;
        }
    }
}
