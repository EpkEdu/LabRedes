package com.example.dns;

import java.util.ArrayList;
import java.util.List;

public class DnsResponseParser {

    public static ParsedDnsResponse parse(byte[] response) {

        int flags = readUnsignedShort(response, 2);

        int rcode = flags & 0x000F;

        int answerCount = readUnsignedShort(response, 6);

        List<String> ips = new ArrayList<>();

        int index = skipQuestionSection(response);

        for (int i = 0; i < answerCount; i++) {

            index = skipName(response, index);

            int type = readUnsignedShort(response, index);
            index += 2;

            int dnsClass = readUnsignedShort(response, index);
            index += 2;

            index += 4;

            int dataLength = readUnsignedShort(response, index);
            index += 2;

            if (type == 1 && dnsClass == 1 && dataLength == 4) {

                String ip =
                        (response[index] & 0xFF) + "." +
                        (response[index + 1] & 0xFF) + "." +
                        (response[index + 2] & 0xFF) + "." +
                        (response[index + 3] & 0xFF);

                ips.add(ip);
            }

            index += dataLength;
        }

        return new ParsedDnsResponse(
                rcode,
                rcodeToString(rcode),
                ips
        );
    }

    private static int skipQuestionSection(byte[] response) {

        int index = 12;

        while (response[index] != 0) {
            index += (response[index] & 0xFF) + 1;
        }

        index++;

        index += 4;

        return index;
    }

    private static int skipName(byte[] response, int index) {

        int value = response[index] & 0xFF;

        if ((value & 0xC0) == 0xC0) {
            return index + 2;
        }

        while (response[index] != 0) {
            index += (response[index] & 0xFF) + 1;
        }

        return index + 1;
    }

    private static int readUnsignedShort(byte[] data, int index) {

        return ((data[index] & 0xFF) << 8)
                | (data[index + 1] & 0xFF);
    }

    private static String rcodeToString(int rcode) {

        return switch (rcode) {
            case 0 -> "NOERROR";
            case 1 -> "FORMERR";
            case 2 -> "SERVFAIL";
            case 3 -> "NXDOMAIN";
            case 4 -> "NOTIMP";
            case 5 -> "REFUSED";
            default -> "UNKNOWN";
        };
    }
}