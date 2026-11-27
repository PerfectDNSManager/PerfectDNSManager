import java.net.*;
import java.io.*;
import java.util.Arrays;

/** Disposable root AVD fixture only; PDM itself never requires root. */
public final class PrefilterDnsFixture {
    public static void main(String[] args) throws Exception {
        DatagramSocket socket = new DatagramSocket(53, InetAddress.getByName("127.0.0.1"));
        System.out.println("Fixture bound to loopback UDP/53");
        byte[] buffer = new byte[8192];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            try {
                byte[] q = Arrays.copyOf(buffer, packet.getLength());
                int offset = 12;
                StringBuilder name = new StringBuilder();
                while (q[offset] != 0) {
                    int n = q[offset++] & 255;
                    if (n > 63) throw new IOException("Invalid label");
                    if (name.length() > 0) name.append('.');
                    name.append(new String(q, offset, n, "US-ASCII")); offset += n;
                }
                offset++;
                int type = (q[offset] & 255) * 256 + (q[offset + 1] & 255);
                if (name.toString().endsWith(".prefilter.test")) System.out.println(name + " type=" + type);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.write(q, 0, 2); out.writeShort(0x8180); out.writeShort(1);
                out.writeShort(type == 1 || type == 28 ? 1 : 0); out.writeShort(0); out.writeShort(0);
                out.write(q, 12, offset + 4 - 12);
                if (type == 1 || type == 28) {
                    out.writeShort(0xc00c); out.writeShort(type); out.writeShort(1); out.writeInt(0);
                    byte[] address = InetAddress.getByName(type == 1 ? "192.0.2.123" : "2001:db8::123").getAddress();
                    out.writeShort(address.length); out.write(address);
                }
                byte[] reply = bytes.toByteArray();
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (Exception e) { System.out.println(e.getClass().getSimpleName()); }
        }
    }
}
