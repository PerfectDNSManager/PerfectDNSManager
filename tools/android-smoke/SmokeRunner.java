package app.perfectdnsmanager.smoke;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.lang.reflect.*;
import java.net.*;
import java.util.Arrays;
import org.json.JSONObject;

/** No AndroidX/Kotlin/test runtime: exercises the actual optimized application. */
public class SmokeRunner extends Instrumentation {
    private Bundle args;
    private final StringBuilder output = new StringBuilder();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args=arguments; start(); }
    private void check(boolean value, String message) { if(!value) throw new AssertionError(message); }
    private Class<?> appClass(String name) throws Exception { return getTargetContext().getClassLoader().loadClass(name); }
    private void pass(String name) { output.append("PASS: ").append(name).append('\n'); }
    private Method method(Class<?> cls,String name,Class<?>... types) throws Exception { Method m=cls.getDeclaredMethod(name,types);m.setAccessible(true);return m; }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            long page=Os.sysconf(OsConstants._SC_PAGESIZE);
            check(page==Long.parseLong(args.getString("expectedPageSize")),"Page size: "+page);
            pass("actual page size "+page);
            if("tls".equals(args.getString("mode"))) { tls(); result.putString("stream",output.toString()+"ALL PASSED\n");finish(-1,result);return; }
            if(!"network".equals(args.getString("mode"))) { argon(); pairing(); profiles(); screens(); }
            if("true".equals(args.getString("antiDpiChecks"))) antiDpi();
            else if(!"offline".equals(args.getString("mode"))) vpn();
            result.putString("stream",output.toString()+"ALL PASSED\n"); finish(-1,result);
        } catch(Throwable e) { result.putString("stream",output.toString()+android.util.Log.getStackTraceString(e));finish(1,result); }
    }
    private void tls() throws Exception {
        javax.net.ssl.SSLSocketFactory factory=(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
        try(javax.net.ssl.SSLSocket socket=(javax.net.ssl.SSLSocket)factory.createSocket("one.one.one.one",443)) {
            socket.setSoTimeout(15000);socket.startHandshake();pass("TLS handshake: "+socket.getSession().getProtocol());
        }
    }
    private void screens() throws Exception {
        for(String name:new String[]{"MainActivity","SettingsActivity","DnsSelectionActivity","LanguageSelectionActivity"}) {
            android.app.Activity activity=startActivitySync(new Intent().setClassName(getTargetContext(),"app.perfectdnsmanager."+name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(activity!=null,"Launch "+name);
            runOnMainSync(activity::finish);
        }
        pass("optimized main, settings, DNS selection and language screens");
    }
    private void argon() throws Exception {
        Class<?> cls=appClass("app.perfectdnsmanager.util.EncryptedSharer$Companion");
        Constructor<?> ctor=cls.getDeclaredConstructor();ctor.setAccessible(true);Object instance=ctor.newInstance();
        byte[] hash=(byte[])method(cls,"argon2id",byte[].class,byte[].class,int.class).invoke(instance,"pdm-test-password".getBytes("UTF-8"),"pdm-test-salt-16!".getBytes("UTF-8"),16);
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        check("10c4a1edca412df1d3d067b556e2c6c1".equals(hex.toString()),"Argon2 vector");pass("production Argon2 vector");
    }
    private void pairing() throws Exception {
        if(Build.VERSION.SDK_INT<30){pass("ADB OS feature correctly not required below API 30");return;}
        Class<?> client=appClass("moe.shizuku.manager.adb.AdbPairingClient");
        check((Boolean)method(client,"available").invoke(null),"ADB native load");
        Class<?> cls=appClass("moe.shizuku.manager.adb.PairingContext");
        Method create=method(cls,"nativeConstructor",boolean.class,byte[].class);
        Constructor<?> ctor=cls.getDeclaredConstructor(long.class);ctor.setAccessible(true);
        Object a=ctor.newInstance(create.invoke(null,true,"synthetic secret".getBytes("UTF-8")));
        Object b=ctor.newInstance(create.invoke(null,false,"synthetic secret".getBytes("UTF-8")));
        Method destroy=method(cls,"destroy"), msg=method(cls,"getMsg"), init=method(cls,"initCipher",byte[].class), encrypt=method(cls,"encrypt",byte[].class), decrypt=method(cls,"decrypt",byte[].class);
        try {
            check((Boolean)init.invoke(a,msg.invoke(b)),"SPAKE client");check((Boolean)init.invoke(b,msg.invoke(a)),"SPAKE server");
            byte[] plain=new byte[8192];for(int i=0;i<plain.length;i++)plain[i]=(byte)(i%251);
            byte[] encrypted=(byte[])encrypt.invoke(a,(Object)plain), tampered=encrypted.clone();tampered[tampered.length-1]^=1;
            check(decrypt.invoke(b,(Object)tampered)==null,"Tampered authentication tag rejected");
            check(Arrays.equals(plain,(byte[])decrypt.invoke(b,(Object)encrypted)),"Full PeerInfo exchange");
            check(encrypt.invoke(a,(Object)new byte[16385])==null,"Oversized input rejected");
        } finally {destroy.invoke(a);destroy.invoke(a);destroy.invoke(b);destroy.invoke(b);}
        check(encrypt.invoke(a,(Object)new byte[1])==null,"Destroyed context rejected");pass("native ADB full PeerInfo, input bounds and repeated close");
    }
    private void profiles() throws Exception {
        Class<?> cls=appClass("app.perfectdnsmanager.data.ConfigManager");Object manager=cls.getConstructor(Context.class).newInstance(getTargetContext());
        String old="{\"profiles\":[{\"id\":123,\"providerName\":\"Custom\",\"name\":\"Legacy smoke\",\"type\":\"DOH\",\"primary\":\"https://example.com/dns-query\",\"isCustom\":true}]}";
        method(cls,"importConfig",String.class,boolean.class).invoke(manager,old,false);
        String exported=(String)method(cls,"exportConfigSelective",boolean.class,boolean.class,boolean.class,boolean.class,boolean.class).invoke(manager,true,false,false,false,false);
        org.json.JSONArray profiles=new JSONObject(exported).getJSONArray("profiles");boolean found=false;
        for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if("Legacy smoke".equals(p.optString("name"))) {check("https://example.com/dns-query".equals(p.getString("primary")),"Persisted endpoint");check("DOH".equals(p.getString("type")),"Persisted type");found=true;}}
        check(found,"Imported profile roundtrip");pass("legacy configuration import/export through production Gson");
    }
    private boolean fullVpn() {
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getTargetContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        for(android.net.Network n:cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            android.net.LinkProperties lp=cm.getLinkProperties(n);
            if(caps!=null&&lp!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                for(android.net.RouteInfo r:lp.getRoutes())if(r.getDestination().getPrefixLength()==0&&r.getDestination().getAddress() instanceof java.net.Inet4Address)return true;
        }
        return false;
    }
    private void remoteProbe(boolean blocked) throws Exception {
        String options=blocked?"-e dnsOnly true -e rcode 3":"";
        if(!blocked && "true".equals(args.getString("ipv6Fixture")))options+=" -e ipv6Fixture true";
        android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("am instrument -w "+options+" app.perfectdnsmanager.antidpiprobe/.Probe");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }
        String text=out.toString("UTF-8");check(text.contains("ALL PASSED"),text);
    }
    private void antiDpi() throws Exception {
        Context context=getTargetContext();
        android.app.Activity activity=startActivitySync(new Intent().setClassName(context,"app.perfectdnsmanager.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Intent consent=android.net.VpnService.prepare(context);
        if(consent!=null && "true".equals(args.getString("allowConsentDialog"))) {
            runOnMainSync(() -> activity.startActivityForResult(consent,189));
            long end=SystemClock.elapsedRealtime()+60000;
            while(android.net.VpnService.prepare(context)!=null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
        }
        check(android.net.VpnService.prepare(context)==null,"VPN consent on disposable AVD");
        context.getSharedPreferences("prefs",0).edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",true).putBoolean("disable_ipv6",false).commit();
        Intent start=new Intent("app.perfectdnsmanager.START_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService").putExtra("dns_primary",args.getString("endpoint","https://one.one.one.one/dns-query"));
        try {
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(start);else context.startService(start);
            long end=SystemClock.elapsedRealtime()+30000;
            while(!fullVpn()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
            check(fullVpn(),"Optimized anti-DPI full VPN");SystemClock.sleep(500);remoteProbe(false);
            Class<?> config=appClass("app.perfectdnsmanager.data.ConfigManager");Object manager=config.getConstructor(Context.class).newInstance(context);
            String filter="{\"dnsPrefilter\":{\"enabled\":true,\"builtin\":false,\"blocked\":\"example.com\",\"allowed\":\"\"}}";
            method(config,"importConfig",String.class,boolean.class).invoke(manager,filter,true);SystemClock.sleep(300);remoteProbe(true);
            method(config,"importConfig",String.class,boolean.class).invoke(manager,filter.replace("true","false"),true);
            pass("optimized anti-DPI: separate UID TCP/TLS/UDP, selected DNS A/AAAA and local prefilter"+("true".equals(args.getString("ipv6Fixture"))?", IPv6 fixture":""));
        } finally {
            context.startService(new Intent("app.perfectdnsmanager.STOP_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService"));
            context.getSharedPreferences("prefs",0).edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",false).commit();
        }
    }
    private void vpn() throws Exception {
        Context context=getTargetContext();
        Intent consent=android.net.VpnService.prepare(context);
        if(consent!=null && "true".equals(args.getString("allowConsentDialog"))) {
            android.app.Activity activity=startActivitySync(new Intent().setClassName(context,"app.perfectdnsmanager.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> activity.startActivityForResult(consent, 187));
            long until=SystemClock.elapsedRealtime()+60000;
            while(android.net.VpnService.prepare(context)!=null && SystemClock.elapsedRealtime()<until)SystemClock.sleep(200);
        }
        check(android.net.VpnService.prepare(context)==null,"VPN consent must be granted on disposable AVD");
        context.getSharedPreferences("prefs",0).edit().putBoolean("disable_ipv6",false).commit();
        startActivitySync(new Intent().setClassName(context,"app.perfectdnsmanager.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        String endpoint=args.getString("endpoint","https://one.one.one.one/dns-query");
        Intent start=new Intent("app.perfectdnsmanager.START_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService").putExtra("dns_primary",endpoint);
        try {
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(start);else context.startService(start);
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean vpnReady=false;
            for(int i=0;i<300&&!vpnReady;i++) {
                for(android.net.Network network:cm.getAllNetworks()) {
                    android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                    android.net.LinkProperties lp=cm.getLinkProperties(network);
                    if(caps!=null&&lp!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                        for(InetAddress server:lp.getDnsServers())if("192.0.2.2".equals(server.getHostAddress()))vpnReady=true;
                }
                if(!vpnReady)SystemClock.sleep(100);
            }
            check(vpnReady,"PDM VPN network must exist before DNS probes (port 53 may be intercepted outside it)");
            SystemClock.sleep(500);
            if("true".equals(args.getString("recursiveBlocked"))) {
                check("unbound://recursive".equals(endpoint),"Blocked-network check requires explicit recursive mode");
                dns("example.com",1,2,false);
                Class<?> config=appClass("app.perfectdnsmanager.data.ConfigManager");
                Object manager=config.getConstructor(Context.class).newInstance(context);
                String filter="{\"dnsPrefilter\":{\"enabled\":true,\"builtin\":false,\"blocked\":\"example.com\",\"allowed\":\"\"}}";
                method(config,"importConfig",String.class,boolean.class).invoke(manager,filter,true);
                SystemClock.sleep(300);dns("example.com",1,3,false);dns("example.com",28,3,false);
                method(config,"importConfig",String.class,boolean.class).invoke(manager,filter.replace("true","false"),true);
                pass("recursive VPN on intercepted network: explicit SERVFAIL; local A/AAAA prefilter still works");
                return;
            }
            Throwable last=null;boolean ready=false;
            for(int i=0;i<15&&!ready;i++){try{dns(1);ready=true;}catch(Throwable e){last=e;SystemClock.sleep(500);}}
            if(!ready)throw new AssertionError("VPN DNS readiness",last);
            dns(28);
            if ("true".equals(args.getString("unboundChecks"))) {
                dns("cloudflare.com",1,0,true);dns("cloudflare.com",28,0,true);
                dns("dnssec-failed.org",1,2,false);
                Class<?> config=appClass("app.perfectdnsmanager.data.ConfigManager");
                Object manager=config.getConstructor(Context.class).newInstance(context);
                String filter="{\"dnsPrefilter\":{\"enabled\":true,\"builtin\":false,\"blocked\":\"example.com\",\"allowed\":\"\"}}";
                method(config,"importConfig",String.class,boolean.class).invoke(manager,filter,true);
                SystemClock.sleep(300);
                dns("example.com",1,3,false);dns("example.com",28,3,false);
                method(config,"importConfig",String.class,boolean.class).invoke(manager,filter.replace("true","false"),true);
                SystemClock.sleep(300);dns(1);dns(28);
                pass("Unbound DNSSEC A/AAAA, bogus signatures rejected and local prefilter A/AAAA");
            }
            if ("true".equals(args.getString("ipv6Toggle"))) ipv6Toggle();
            if ("true".equals(args.getString("dnsOnly"))) {
                pass("optimized VPN "+endpoint+": A and AAAA; IPv6 block disabled (separate HTTPS probe omitted)");
                return;
            }
            javax.net.ssl.HttpsURLConnection connection=(javax.net.ssl.HttpsURLConnection)new URL("https://example.com/").openConnection();
            connection.setConnectTimeout(20000);connection.setReadTimeout(20000);
            try {check(connection.getResponseCode()==200,"HTTPS after DNS VPN");}finally{connection.disconnect();}
            pass("optimized VPN "+endpoint+": A, AAAA and HTTPS; IPv6 block disabled");
        } finally {context.startService(new Intent("app.perfectdnsmanager.STOP_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService"));}
    }
    private void ipv6Toggle() throws Exception {
        Context context=getTargetContext();
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for(boolean blocked : new boolean[]{false,true,false}) {
            context.getSharedPreferences("prefs",0).edit().putBoolean("disable_ipv6",blocked).commit();
            boolean matched=false;
            for(int i=0;i<100&&!matched;i++) {
                for(android.net.Network n:cm.getAllNetworks()) {
                    android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                    android.net.LinkProperties lp=cm.getLinkProperties(n);
                    if(caps==null||lp==null||!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))continue;
                    boolean hasBlock=false;
                    for(android.net.RouteInfo route:lp.getRoutes())
                        if(route.getDestination().getAddress() instanceof java.net.Inet6Address && route.getDestination().getPrefixLength()==0)hasBlock=true;
                    matched=hasBlock==blocked;
                }
                if(!matched)SystemClock.sleep(100);
            }
            check(matched,"Live IPv6 route matches preference: "+blocked);
            Throwable last=null;boolean ready=false;
            for(int i=0;i<10&&!ready;i++) {
                try { dns(1);dns(28);ready=true; }
                catch(Throwable error) { last=error;SystemClock.sleep(300); }
            }
            if(!ready)throw new AssertionError("DNS after IPv6 route change: "+blocked,last);
        }
        pass("IPv6 routes OFF/ON/OFF follow direct preference changes; A/AAAA continue resolving");
    }
    private void dns(int type) throws Exception { dns("example.com",type,0,false,3000); }
    private void dns(String name,int type,int rcode,boolean secure) throws Exception { dns(name,type,rcode,secure,25000); }
    private void dns(String name,int type,int rcode,boolean secure,int timeout) throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        out.write(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        for(String label:name.split("\\.")){byte[] b=label.getBytes("US-ASCII");out.write(b.length);out.write(b);}
        out.write(new byte[]{0,0,(byte)type,0,1});byte[] q=out.toByteArray();
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.setSoTimeout(timeout);socket.send(new DatagramPacket(q,q.length,InetAddress.getByName("192.0.2.2"),53));
            byte[] bytes=new byte[8192];DatagramPacket response=new DatagramPacket(bytes,bytes.length);socket.receive(response);
            check(response.getLength()>12 && bytes[0]==q[0] && bytes[1]==q[1] && (bytes[3]&15)==rcode,"DNS response for "+name+" type "+type);
            if(rcode==0)check(((bytes[6]&255)*256+(bytes[7]&255))>0,"DNS answer type "+type);
            if(secure)check((bytes[3]&32)!=0,"Validated DNSSEC AD flag");
        }
    }
}
