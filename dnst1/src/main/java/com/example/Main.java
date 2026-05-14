package com.example;

import com.example.analysis.BenchmarkService;
import com.example.analysis.BlockDetector;
import com.example.dns.*;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("Uso: java Main <dominio>");
            return;
        }

        String domain = args[0];

        List<DnsServer> servers = List.of(
                new DnsServer("Google", "8.8.8.8"),
                new DnsServer("Cloudflare", "1.1.1.1"),
                new DnsServer("Quad9", "9.9.9.9"),
                new DnsServer("OpenDNS", "208.67.222.222")
        );

        System.out.println("========================================");
        System.out.println("Consulta DNS UDP");
        System.out.println("Domínio: " + domain);
        System.out.println("========================================\n");

        for (DnsServer server : servers) {
            executeSingleQuery(server, domain);
        }

        System.out.println("\n========================================");
        System.out.println("Benchmark");
        System.out.println("========================================\n");

        BenchmarkService.run(servers, "www.example.com");
    }

    private static void executeSingleQuery(DnsServer server, String domain) {

        try {

            byte[] query = DnsMessageBuilder.buildQuery(domain);

            long start = System.nanoTime();

            byte[] responseBytes = DnsClient.sendQuery(
                    server.ip(),
                    query
            );

            long end = System.nanoTime();

            double timeMs = (end - start) / 1_000_000.0;

            ParsedDnsResponse response =
                    DnsResponseParser.parse(responseBytes);

            System.out.println("Servidor: " + server.name());
            System.out.println("IP: " + server.ip());
            System.out.printf("Tempo: %.2f ms%n", timeMs);
            System.out.println("RCODE: " + response.rcodeDescription());

            if (response.ipAddresses().isEmpty()) {
                System.out.println("IPs: nenhum");
            } else {
                System.out.println("IPs:");
                response.ipAddresses().forEach(ip ->
                        System.out.println(" - " + ip));
            }

            String detection = BlockDetector.detect(response);

            if (detection != null) {
                System.out.println("ALERTA: " + detection);
            }

            System.out.println("----------------------------------------");

        } catch (Exception e) {
            System.out.println("Servidor: " + server.name());
            System.out.println("Erro: " + e.getMessage());
            System.out.println("----------------------------------------");
        }
    }
}