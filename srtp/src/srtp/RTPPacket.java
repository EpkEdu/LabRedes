package srtp;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Representa um pacote do protocolo SRTP.
 *
 * Layout do cabeçalho (9 bytes):
 *
 * Byte 0-1 (16 bits):
 *   bit 15:     SYN flag
 *   bit 14:     FIN flag
 *   bits 13-0:  SEQ (14 bits)
 *
 * Byte 2-3 (16 bits):
 *   bit 15:     ACK flag
 *   bit 14:     NACK flag
 *   bits 13-0:  ACK number (14 bits)
 *
 * Byte 4 (8 bits):  Length
 * Bytes 5-8 (32 bits): CRC32
 */
public class RTPPacket {

    public static final int HEADER_SIZE = 9;
    public static final int MAX_PAYLOAD  = 255;
    public static final int SEQ_MAX      = 16384; // 2^14

    // Flags (bits no primeiro word)
    private static final int SYN_BIT  = 1 << 15;
    private static final int FIN_BIT  = 1 << 14;
    private static final int SEQ_MASK = 0x3FFF;

    // Flags (bits no segundo word)
    private static final int ACKF_BIT  = 1 << 15;
    private static final int NACK_BIT  = 1 << 14;
    private static final int ACK_MASK  = 0x3FFF;

    public boolean syn;
    public boolean fin;
    public int     seq;   // 14 bits
    public boolean ackFlag;
    public boolean nack;
    public int     ack;   // 14 bits
    public int     length; // 8 bits
    public byte[]  payload;

    public RTPPacket() {
        payload = new byte[0];
    }

    /** Serializa o pacote em bytes (cabeçalho + payload) com CRC32 calculado. */
    public byte[] toBytes() {
        int totalSize = HEADER_SIZE + (payload != null ? payload.length : 0);
        ByteBuffer buf = ByteBuffer.allocate(totalSize);

        // Word 0-1: SYN | FIN | SEQ(14)
        int word0 = (seq & SEQ_MASK);
        if (syn) word0 |= SYN_BIT;
        if (fin) word0 |= FIN_BIT;
        buf.putShort((short) word0);

        // Word 2-3: ACKflag | NACK | ACK(14)
        int word1 = (ack & ACK_MASK);
        if (ackFlag) word1 |= ACKF_BIT;
        if (nack)    word1 |= NACK_BIT;
        buf.putShort((short) word1);

        // Byte 4: Length
        buf.put((byte) (length & 0xFF));

        // Bytes 5-8: CRC32 zerado para cálculo
        buf.putInt(0);

        // Payload
        if (payload != null && payload.length > 0) {
            buf.put(payload);
        }

        // Calcula CRC32 sobre tudo
        byte[] raw = buf.array();
        long crc = computeCRC32(raw);

        // Insere CRC32 nos bytes 5-8
        raw[5] = (byte) ((crc >> 24) & 0xFF);
        raw[6] = (byte) ((crc >> 16) & 0xFF);
        raw[7] = (byte) ((crc >> 8)  & 0xFF);
        raw[8] = (byte) ((crc)       & 0xFF);

        return raw;
    }

    /**
     * Desserializa bytes em um RTPPacket.
     * Retorna null se CRC32 for inválido.
     */
    public static RTPPacket fromBytes(byte[] data, int offset, int len) {
        if (len < HEADER_SIZE) return null;

        // Verifica CRC32: zera o campo CRC e recalcula
        byte[] copy = new byte[len];
        System.arraycopy(data, offset, copy, 0, len);

        // Lê CRC do pacote
        long receivedCRC = ((copy[5] & 0xFFL) << 24)
                         | ((copy[6] & 0xFFL) << 16)
                         | ((copy[7] & 0xFFL) << 8)
                         |  (copy[8] & 0xFFL);

        // Zera campo CRC para recalcular
        copy[5] = 0; copy[6] = 0; copy[7] = 0; copy[8] = 0;
        long computedCRC = computeCRC32(copy);

        if (receivedCRC != computedCRC) {
            System.err.println("[CRC32] Pacote corrompido descartado. Recebido=" +
                    Long.toHexString(receivedCRC) + " Calculado=" + Long.toHexString(computedCRC));
            return null; // Descarta silenciosamente
        }

        RTPPacket pkt = new RTPPacket();
        ByteBuffer buf = ByteBuffer.wrap(copy);

        int word0 = buf.getShort() & 0xFFFF;
        pkt.syn = (word0 & SYN_BIT) != 0;
        pkt.fin = (word0 & FIN_BIT) != 0;
        pkt.seq = word0 & SEQ_MASK;

        int word1 = buf.getShort() & 0xFFFF;
        pkt.ackFlag = (word1 & ACKF_BIT) != 0;
        pkt.nack    = (word1 & NACK_BIT) != 0;
        pkt.ack     = word1 & ACK_MASK;

        pkt.length = buf.get() & 0xFF;

        buf.getInt(); // CRC32 (já validado)

        int payloadLen = len - HEADER_SIZE;
        if (payloadLen > 0) {
            pkt.payload = new byte[payloadLen];
            buf.get(pkt.payload);
        } else {
            pkt.payload = new byte[0];
        }

        return pkt;
    }

    private static long computeCRC32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** Cria um pacote SYN para handshake. */
    public static RTPPacket createSYN(int windowSize) {
        RTPPacket p = new RTPPacket();
        p.syn    = true;
        p.length = windowSize & 0xFF;
        return p;
    }

    /** Cria um pacote SYN+ACK para handshake. */
    public static RTPPacket createSYNACK(int windowSize) {
        RTPPacket p = new RTPPacket();
        p.syn     = true;
        p.ackFlag = true;
        p.length  = windowSize & 0xFF;
        return p;
    }

    /** Cria um pacote ACK puro. */
    public static RTPPacket createACK(int ackNum) {
        RTPPacket p = new RTPPacket();
        p.ackFlag = true;
        p.ack     = ackNum & SEQ_MASK;
        return p;
    }

    /** Cria um pacote NACK. */
    public static RTPPacket createNACK(int seqExpected) {
        RTPPacket p = new RTPPacket();
        p.ackFlag = true;
        p.nack    = true;
        p.ack     = seqExpected & SEQ_MASK;
        return p;
    }

    /** Cria um pacote FIN. */
    public static RTPPacket createFIN() {
        RTPPacket p = new RTPPacket();
        p.fin    = true;
        p.length = 0;
        return p;
    }

    /** Cria um pacote FIN+ACK. */
    public static RTPPacket createFINACK() {
        RTPPacket p = new RTPPacket();
        p.fin     = true;
        p.ackFlag = true;
        return p;
    }

    /** Cria um pacote de dados. */
    public static RTPPacket createData(int seq, byte[] payload, int offset, int len) {
        RTPPacket p = new RTPPacket();
        p.seq     = seq & SEQ_MASK;
        p.length  = len & 0xFF;
        p.payload = new byte[len];
        System.arraycopy(payload, offset, p.payload, 0, len);
        return p;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RTPPacket{");
        if (syn)     sb.append("SYN ");
        if (fin)     sb.append("FIN ");
        if (ackFlag) sb.append("ACK ");
        if (nack)    sb.append("NACK ");
        sb.append("seq=").append(seq);
        sb.append(" ack=").append(ack);
        sb.append(" len=").append(length);
        sb.append(" payload=").append(payload != null ? payload.length : 0).append("B}");
        return sb.toString();
    }
}
