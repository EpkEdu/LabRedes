package srtp;

import java.io.IOException;
import java.net.*;

/**
 * Wrapper sobre DatagramSocket para envio/recepção de pacotes RTP.
 */
public class RTPSocket implements AutoCloseable {

    private static final int BUFFER_SIZE = RTPPacket.HEADER_SIZE + RTPPacket.MAX_PAYLOAD + 64;

    private final DatagramSocket socket;

    public RTPSocket(int localPort) throws SocketException {
        socket = new DatagramSocket(localPort);
    }

    public RTPSocket() throws SocketException {
        socket = new DatagramSocket();
    }

    /** Envia um pacote RTP para o destino especificado. */
    public void send(RTPPacket pkt, InetAddress addr, int port) throws IOException {
        byte[] data = pkt.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, addr, port);
        socket.send(dp);
    }

    /**
     * Recebe um pacote RTP (bloqueia até receber ou timeout).
     * Retorna null se pacote for inválido (CRC32 falhou) ou timeout ocorreu.
     */
    public ReceivedPacket receive() throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);

        RTPPacket pkt = RTPPacket.fromBytes(dp.getData(), dp.getOffset(), dp.getLength());
        if (pkt == null) return null; // CRC32 inválido, descarta

        return new ReceivedPacket(pkt, dp.getAddress(), dp.getPort());
    }

    public void setSoTimeout(int ms) throws SocketException {
        socket.setSoTimeout(ms);
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        socket.close();
    }

    /** Contêiner para pacote recebido com metadados de endereço. */
    public static class ReceivedPacket {
        public final RTPPacket  packet;
        public final InetAddress address;
        public final int         port;

        public ReceivedPacket(RTPPacket packet, InetAddress address, int port) {
            this.packet  = packet;
            this.address = address;
            this.port    = port;
        }
    }
}
