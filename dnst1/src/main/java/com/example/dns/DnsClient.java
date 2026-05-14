package com.example.dns;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class DnsClient {

    private static final int DNS_PORT = 53;
    private static final int BUFFER_SIZE = 512;
    private static final int TIMEOUT_MS = 3000;

    public static byte[] sendQuery(
            String dnsServer,
            byte[] query
    ) throws IOException {

        try (DatagramSocket socket = new DatagramSocket()) {

            socket.setSoTimeout(TIMEOUT_MS);

            InetAddress address =
                    InetAddress.getByName(dnsServer);

            DatagramPacket request =
                    new DatagramPacket(
                            query,
                            query.length,
                            address,
                            DNS_PORT
                    );

            socket.send(request);

            byte[] responseBuffer =
                    new byte[BUFFER_SIZE];

            DatagramPacket response =
                    new DatagramPacket(
                            responseBuffer,
                            responseBuffer.length
                    );

            socket.receive(response);

            byte[] data = new byte[response.getLength()];

            System.arraycopy(
                    response.getData(),
                    0,
                    data,
                    0,
                    response.getLength()
            );

            return data;

        } catch (SocketTimeoutException e) {
            throw new IOException("Timeout na consulta DNS");
        }
    }
}