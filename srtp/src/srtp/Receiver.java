package srtp;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Receiver SRTP — Stop-and-Wait (Parte 1).
 *
 * Modelo de portas (conforme especificação):
 *   - Receiver escuta em P para SYN, dados e FIN
 *   - Receiver envia SYN+ACK / ACKs / FIN+ACK para sender_IP:(P+1)
 *     (sender escuta em P+1 após o handshake)
 *   - Durante o handshake, SYN+ACK vai para a porta efêmera do sender (rp.port)
 *     pois o sender ainda não está em P+1
 *
 * Fluxo:
 *  1. Aguarda SYN → responde SYN+ACK → aguarda ACK (three-way handshake)
 *  2. Recebe dados em ordem → envia ACK para cada pacote (stop-and-wait)
 *  3. Recebe FIN → responde FIN+ACK (two-way teardown)
 *  4. Salva arquivo no disco
 */
public class Receiver {

    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int DATA_TIMEOUT_MS      = 10000;
    private static final int MAX_RETRIES          = 10;

    private final int  port;
    private final File outputFile;
    private final int  windowSize; // proposta do receiver no SYN+ACK

    private final List<byte[]> receivedChunks = new ArrayList<>();

    // Estatísticas
    private int  totalPacketsReceived = 0;
    private int  totalACKsSent        = 0;
    private int  droppedPackets       = 0;
    private long receiveStartTime     = 0;
    private long receiveEndTime       = 0;

    public Receiver(int port, File outputFile, int windowSize) {
        this.port       = port;
        this.outputFile = outputFile;
        this.windowSize = windowSize;
    }

    public void run() throws IOException {
        System.out.println("[Receiver] Aguardando conexão na porta " + port + "...");

        try (RTPSocket sock = new RTPSocket(port)) {
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            // ─── FASE 1: Handshake ─────────────────────────────────────────────
            InetAddress senderAddr;
            int         senderAckPort; // P+1 do sender (onde ele aguarda ACKs)
            int         senderHandshakePort; // porta efêmera usada no handshake

            System.out.println("[Receiver] Aguardando SYN...");
            RTPPacket synAck = null;

            // Loop até receber SYN válido
            while (true) {
                try {
                    RTPSocket.ReceivedPacket rp = sock.receive();
                    if (rp == null) continue;

                    RTPPacket pkt = rp.packet;
                    if (pkt.syn && !pkt.ackFlag && !pkt.fin) {
                        senderAddr         = rp.address;
                        senderHandshakePort = rp.port;
                        // Sender escutará ACKs em P+1 após handshake
                        senderAckPort       = port + 1;

                        int senderWindow   = pkt.length == 0 ? 1 : pkt.length;
                        int effectiveWindow = Math.min(windowSize, senderWindow);
                        if (effectiveWindow == 0) effectiveWindow = 1;

                        System.out.println("[Receiver] SYN de " + senderAddr +
                                ":" + senderHandshakePort +
                                " | janela sender=" + senderWindow +
                                " receiver=" + windowSize +
                                " efetiva=" + effectiveWindow);

                        synAck = RTPPacket.createSYNACK(windowSize);

                        // Envia SYN+ACK para a porta efêmera do handshake
                        sock.send(synAck, senderAddr, senderHandshakePort);
                        System.out.println("[Receiver] SYN+ACK enviado.");

                        // Aguarda ACK final do handshake
                        boolean gotAck = false;
                        for (int i = 0; i < MAX_RETRIES && !gotAck; i++) {
                            try {
                                RTPSocket.ReceivedPacket rp2 = sock.receive();
                                if (rp2 == null) continue;
                                RTPPacket p2 = rp2.packet;
                                if (p2.ackFlag && !p2.syn && !p2.fin && !p2.nack) {
                                    System.out.println("[Receiver] ACK de handshake recebido. Sessão estabelecida.");
                                    gotAck = true;
                                }
                            } catch (SocketTimeoutException e) {
                                System.out.println("[Receiver] Timeout, reenviando SYN+ACK...");
                                sock.send(synAck, senderAddr, senderHandshakePort);
                            }
                        }
                        if (!gotAck) throw new IOException("Handshake incompleto.");

                        // ─── FASE 2: Transferência ─────────────────────────────
                        sock.setSoTimeout(DATA_TIMEOUT_MS);
                        receiveStartTime = System.currentTimeMillis();
                        doTransfer(sock, senderAddr, senderAckPort);
                        receiveEndTime = System.currentTimeMillis();

                        saveFile();
                        printStats();
                        return;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("[Receiver] Aguardando SYN...");
                }
            }
        }
    }

    // ─── Recepção de Dados (Stop-and-Wait) ──────────────────────────────────────

    private void doTransfer(RTPSocket sock, InetAddress senderAddr, int senderAckPort)
            throws IOException {

        System.out.println("[Receiver] Aguardando dados. ACKs → " +
                senderAddr + ":" + senderAckPort);

        int expectedSeq = 0;

        while (true) {
            try {
                RTPSocket.ReceivedPacket rp = sock.receive();
                if (rp == null) {
                    // CRC32 inválido — descarta silenciosamente, sem NACK
                    droppedPackets++;
                    System.err.println("[Receiver] Pacote corrompido (CRC32) descartado silenciosamente.");
                    continue;
                }

                RTPPacket pkt = rp.packet;

                // ─── FIN (encerramento) ──────────────────────────────────────
                if (pkt.fin && !pkt.syn) {
                    System.out.println("[Receiver] FIN recebido. Enviando FIN+ACK...");
                    RTPPacket finAck = RTPPacket.createFINACK();
                    sock.send(finAck, senderAddr, senderAckPort);
                    return;
                }

                // ─── Pacote de dados ─────────────────────────────────────────
                if (pkt.seq == expectedSeq) {
                    totalPacketsReceived++;

                    if (pkt.payload != null && pkt.payload.length > 0) {
                        receivedChunks.add(pkt.payload.clone());
                    }

                    // ACK para o sender (na porta P+1)
                    RTPPacket ack = RTPPacket.createACK(expectedSeq);
                    sock.send(ack, senderAddr, senderAckPort);
                    totalACKsSent++;
                    System.out.println("[Receiver] SEQ=" + pkt.seq +
                            " len=" + pkt.length + " → ACK=" + expectedSeq + " enviado.");

                    if (pkt.length < RTPPacket.MAX_PAYLOAD) {
                        System.out.println("[Receiver] Último pacote (len=" + pkt.length +
                                "). Aguardando FIN...");
                    }

                    expectedSeq = (expectedSeq + 1) % RTPPacket.SEQ_MAX;

                } else {
                    // Fora de ordem — descarta silenciosamente (SAW)
                    droppedPackets++;
                    System.out.println("[Receiver] Fora de ordem: SEQ=" + pkt.seq +
                            " esperado=" + expectedSeq + ". Descartado.");

                    // Reenvia ACK do último pacote aceito
                    int lastGood = (expectedSeq - 1 + RTPPacket.SEQ_MAX) % RTPPacket.SEQ_MAX;
                    RTPPacket ack = RTPPacket.createACK(lastGood);
                    sock.send(ack, senderAddr, senderAckPort);
                }

            } catch (SocketTimeoutException e) {
                System.out.println("[Receiver] Timeout aguardando pacote SEQ=" + expectedSeq + "...");
            }
        }
    }

    // ─── Salva arquivo recebido ──────────────────────────────────────────────────

    private void saveFile() throws IOException {
        if (outputFile.getParentFile() != null) {
            outputFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (byte[] chunk : receivedChunks) {
                fos.write(chunk);
            }
        }
        long total = receivedChunks.stream().mapToLong(b -> b.length).sum();
        System.out.println("[Receiver] Arquivo salvo: " + outputFile.getAbsolutePath() +
                " (" + total + " bytes)");
    }

    private void printStats() {
        long elapsed  = receiveEndTime - receiveStartTime;
        long fileSize = receivedChunks.stream().mapToLong(b -> b.length).sum();
        double throughputKbps = elapsed > 0
                ? (fileSize * 8.0 / 1000.0) / (elapsed / 1000.0)
                : 0;

        System.out.println("\n========== ESTATÍSTICAS ==========");
        System.out.println("Arquivo          : " + outputFile.getName());
        System.out.println("Tamanho          : " + fileSize + " bytes");
        System.out.println("Tempo            : " + elapsed + " ms");
        System.out.printf( "Throughput       : %.2f kbps%n", throughputKbps);
        System.out.println("Pacotes recebidos: " + totalPacketsReceived);
        System.out.println("ACKs enviados    : " + totalACKsSent);
        System.out.println("Pacotes descart. : " + droppedPackets);
        System.out.println("===================================\n");
    }
}
