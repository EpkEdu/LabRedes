package srtp;

import java.io.*;
import java.net.*;

/**
 * Sender SRTP — Stop-and-Wait (Parte 1).
 *
 * Modelo de portas (conforme especificação):
 *   - Sender envia SYN para receiver:P (usando socket efêmero durante handshake)
 *   - Após handshake, sender cria socket em P+1 para ENVIAR dados e RECEBER ACKs
 *   - Receiver descobre P+1 como source port dos pacotes de dados
 *
 * Fluxo:
 *  1. Three-way handshake (SYN → SYN+ACK → ACK) via socket efêmero
 *  2. Transferência stop-and-wait via socket em P+1 com timeout de 100ms
 *  3. Two-way teardown (FIN → FIN+ACK)
 */
public class Sender {

    private static final int TIMEOUT_MS  = 100;
    private static final int MAX_RETRIES = 50;

    private final String remoteHost;
    private final int    port;
    private final File   file;
    private final int    windowSize;

    // Estatísticas
    private int  totalPacketsSent     = 0;
    private int  totalRetransmissions = 0;
    private long transferStartTime    = 0;
    private long transferEndTime      = 0;

    public Sender(String remoteHost, int port, File file, int windowSize) {
        this.remoteHost = remoteHost;
        this.port       = port;
        this.file       = file;
        this.windowSize = windowSize;
    }

    public void run() throws IOException {
        InetAddress remoteAddr = InetAddress.getByName(remoteHost);
        int ackListenPort      = port + 1; // Sender escuta ACKs em P+1

        System.out.println("[Sender] Conectando a " + remoteHost + ":" + port);
        System.out.println("[Sender] Arquivo: " + file.getName() + " (" + file.length() + " bytes)");

        // ─── FASE 1: Handshake (socket efêmero) ──────────────────────────────
        int effectiveWindow;
        try (RTPSocket hsSocket = new RTPSocket()) {
            hsSocket.setSoTimeout(TIMEOUT_MS * 5);
            effectiveWindow = doHandshake(hsSocket, remoteAddr, port);
        }
        System.out.println("[Sender] Handshake completo. Janela efetiva: " + effectiveWindow);

        // ─── FASE 2: Dados + ACKs via socket em P+1 ──────────────────────────
        try (RTPSocket dataSocket = new RTPSocket(ackListenPort)) {
            dataSocket.setSoTimeout(TIMEOUT_MS);

            transferStartTime = System.currentTimeMillis();
            doTransfer(dataSocket, remoteAddr, port);
            transferEndTime = System.currentTimeMillis();
        }

        printStats();
    }

    // ─── Handshake ─────────────────────────────────────────────────────────────

    private int doHandshake(RTPSocket sock, InetAddress remoteAddr, int remotePort)
            throws IOException {

        RTPPacket syn = RTPPacket.createSYN(windowSize);
        int retries   = 0;

        while (retries < MAX_RETRIES) {
            System.out.println("[Sender] Enviando SYN (tentativa " + (retries + 1) + ")");
            sock.send(syn, remoteAddr, remotePort);

            try {
                RTPSocket.ReceivedPacket rp = sock.receive();
                if (rp == null) { retries++; continue; }

                RTPPacket pkt = rp.packet;
                if (pkt.syn && pkt.ackFlag) {
                    int receiverWindow = pkt.length == 0 ? 1 : pkt.length;
                    int effective      = Math.min(windowSize, receiverWindow);
                    if (effective == 0) effective = 1;

                    // ACK final do handshake
                    RTPPacket ack = RTPPacket.createACK(0);
                    sock.send(ack, remoteAddr, remotePort);
                    System.out.println("[Sender] ACK de handshake enviado.");
                    return effective;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[Sender] Timeout no handshake, retransmitindo SYN...");
                retries++;
            }
        }
        throw new IOException("Handshake falhou após " + MAX_RETRIES + " tentativas.");
    }

    // ─── Transferência Stop-and-Wait ────────────────────────────────────────────

    private void doTransfer(RTPSocket sock, InetAddress remoteAddr, int remotePort)
            throws IOException {

        byte[] fileData  = readFile(file);
        int    totalBytes = fileData.length;
        System.out.println("[Sender] Iniciando transferência: " + totalBytes + " bytes");

        int seq    = 0;
        int offset = 0;

        while (true) {
            int remaining  = totalBytes - offset;
            int payloadLen;
            boolean isLast;

            if (remaining == 0) {
                // Edge case: arquivo múltiplo exato de 255 bytes
                payloadLen = 0;
                isLast     = true;
            } else if (remaining >= RTPPacket.MAX_PAYLOAD) {
                payloadLen = RTPPacket.MAX_PAYLOAD;
                isLast     = false; // length=255 → intermediário
            } else {
                payloadLen = remaining;
                isLast     = true;  // length<255 → último
            }

            RTPPacket pkt = RTPPacket.createData(seq, fileData, offset, payloadLen);

            boolean acked = sendAndWait(sock, pkt, seq, remoteAddr, remotePort);
            if (!acked) {
                throw new IOException("Falha ao enviar SEQ=" + seq + " após " + MAX_RETRIES + " tentativas.");
            }

            offset += payloadLen;
            seq     = (seq + 1) % RTPPacket.SEQ_MAX;

            if (isLast) break;
        }

        System.out.println("[Sender] Transferência concluída. Iniciando encerramento...");
        doTeardown(sock, remoteAddr, remotePort);
    }

    /**
     * Envia pacote e aguarda ACK (stop-and-wait puro).
     * Retorna true se ACK confirmado, false se excedeu MAX_RETRIES.
     */
    private boolean sendAndWait(RTPSocket sock, RTPPacket pkt, int expectedAck,
                                InetAddress remoteAddr, int remotePort) throws IOException {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            sock.send(pkt, remoteAddr, remotePort);
            totalPacketsSent++;

            if (retries > 0) {
                totalRetransmissions++;
                System.out.println("[Sender] Retransmissão SEQ=" + pkt.seq +
                        " (tentativa " + (retries + 1) + ")");
            } else {
                System.out.println("[Sender] Enviado SEQ=" + pkt.seq + " len=" + pkt.length);
            }

            try {
                RTPSocket.ReceivedPacket rp = sock.receive();
                if (rp == null) { retries++; continue; } // CRC inválido

                RTPPacket resp = rp.packet;
                if (resp.ackFlag && !resp.nack && resp.ack == expectedAck) {
                    System.out.println("[Sender] ACK=" + expectedAck + " confirmado.");
                    return true;
                }
                System.out.println("[Sender] Resposta inesperada: " + resp + " (esperava ACK=" +
                        expectedAck + ")");
                retries++;

            } catch (SocketTimeoutException e) {
                System.out.println("[Sender] Timeout aguardando ACK para SEQ=" + pkt.seq);
                retries++;
            }
        }
        return false;
    }

    // ─── Teardown ───────────────────────────────────────────────────────────────

    private void doTeardown(RTPSocket sock, InetAddress remoteAddr, int remotePort)
            throws IOException {

        RTPPacket fin = RTPPacket.createFIN();
        int retries   = 0;

        while (retries < MAX_RETRIES) {
            System.out.println("[Sender] Enviando FIN (tentativa " + (retries + 1) + ")");
            sock.send(fin, remoteAddr, remotePort);

            try {
                RTPSocket.ReceivedPacket rp = sock.receive();
                if (rp == null) { retries++; continue; }

                if (rp.packet.fin && rp.packet.ackFlag) {
                    System.out.println("[Sender] FIN+ACK recebido. Sessão encerrada.");
                    return;
                }
                retries++;
            } catch (SocketTimeoutException e) {
                System.out.println("[Sender] Timeout aguardando FIN+ACK...");
                retries++;
            }
        }
        System.err.println("[Sender] Teardown incompleto após " + MAX_RETRIES + " tentativas.");
    }

    // ─── Utilitários ────────────────────────────────────────────────────────────

    private byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = 0;
            while (read < data.length) {
                int n = fis.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return data;
        }
    }

    private void printStats() {
        long elapsed   = transferEndTime - transferStartTime;
        long fileSize  = file.length();
        double throughputKbps = elapsed > 0
                ? (fileSize * 8.0 / 1000.0) / (elapsed / 1000.0)
                : 0;

        System.out.println("\n========== ESTATÍSTICAS ==========");
        System.out.println("Arquivo          : " + file.getName());
        System.out.println("Tamanho          : " + fileSize + " bytes");
        System.out.println("Tempo            : " + elapsed + " ms");
        System.out.printf( "Throughput       : %.2f kbps%n", throughputKbps);
        System.out.println("Pacotes enviados : " + totalPacketsSent);
        System.out.println("Retransmissões   : " + totalRetransmissions);
        System.out.println("===================================\n");
    }
}
