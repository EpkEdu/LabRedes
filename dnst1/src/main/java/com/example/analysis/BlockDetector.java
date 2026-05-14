package com.example.analysis;
import com.example.dns.ParsedDnsResponse;

public class BlockDetector {

    public static String detect(ParsedDnsResponse response) {

        if (response.rcode() == 3) {
            return "Possível bloqueio NXDOMAIN";
        }

        if (response.rcode() == 5) {
            return "Consulta recusada (REFUSED)";
        }

        for (String ip : response.ipAddresses()) {

            if (ip.equals("0.0.0.0")) {
                return "Possível bloqueio via 0.0.0.0";
            }

            if (ip.equals("127.0.0.1")) {
                return "Possível redirecionamento localhost";
            }
        }

        return null;
    }
}