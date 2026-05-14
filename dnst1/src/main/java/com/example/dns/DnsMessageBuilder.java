package com.example.dns;


import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

public class DnsMessageBuilder {

    public static byte[] buildQuery(String domain)
            throws IOException {

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

        DataOutputStream dos =
                new DataOutputStream(baos);

        int transactionId =
                new Random().nextInt(0xFFFF);

        dos.writeShort(transactionId);

        dos.writeShort(0x0100);

        dos.writeShort(1);
        dos.writeShort(0);
        dos.writeShort(0);
        dos.writeShort(0);

        writeDomainName(dos, domain);

        dos.writeShort(1);

        dos.writeShort(1);

        return baos.toByteArray();
    }

    private static void writeDomainName(
            DataOutputStream dos,
            String domain
    ) throws IOException {

        String[] labels = domain.split("\\.");

        for (String label : labels) {
            dos.writeByte(label.length());
            dos.writeBytes(label);
        }

        dos.writeByte(0);
    }
}