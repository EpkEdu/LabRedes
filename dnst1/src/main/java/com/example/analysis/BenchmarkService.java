package com.example.analysis;

import com.example.dns.DnsClient;
import com.example.dns.DnsMessageBuilder;
import com.example.dns.DnsResult;
import com.example.dns.DnsServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BenchmarkService {

    private static final int REQUESTS = 10;

    public static void run(
            List<DnsServer> servers,
            String domain
    ) {

        List<ResultRow> ranking = new ArrayList<>();

        for (DnsServer server : servers) {

            DnsResult result = benchmark(server, domain);

            ranking.add(new ResultRow(server, result));
        }

        ranking.sort(
                Comparator.comparingDouble(
                        row -> row.result().average()
                )
        );

        System.out.println("============= RANKING =============");

        int position = 1;

        for (ResultRow row : ranking) {

            System.out.println(
                    "#" + position + " - " +
                    row.server().name() +
                    " (" + row.server().ip() + ")"
            );

            System.out.printf(
                    "Tempo médio: %.2f ms%n",
                    row.result().average()
            );

            System.out.printf(
                    "Tempo mínimo: %.2f ms%n",
                    row.result().minimum()
            );

            System.out.printf(
                    "Tempo máximo: %.2f ms%n",
                    row.result().maximum()
            );

            System.out.printf(
                    "Perda de pacotes: %.2f%%%n",
                    row.result().packetLoss()
            );

            System.out.println("----------------------------------------");

            position++;
        }
    }

    private static DnsResult benchmark(
            DnsServer server,
            String domain
    ) {

        List<Double> responseTimes = new ArrayList<>();

        int successCount = 0;

        for (int i = 0; i < REQUESTS; i++) {

            try {

                byte[] query =
                        DnsMessageBuilder.buildQuery(domain);

                long start = System.nanoTime();

                DnsClient.sendQuery(
                        server.ip(),
                        query
                );

                long end = System.nanoTime();

                double elapsedMs =
                        (end - start) / 1_000_000.0;

                responseTimes.add(elapsedMs);

                successCount++;

            } catch (Exception e) {

                System.out.println(
                        "Falha em " +
                        server.name() +
                        ": " + e.getMessage()
                );
            }
        }

        double average = responseTimes.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

        double minimum = responseTimes.stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0);

        double maximum = responseTimes.stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0);

        double packetLoss =
                ((REQUESTS - successCount)
                        / (double) REQUESTS) * 100;

        return new DnsResult(
                average,
                minimum,
                maximum,
                packetLoss
        );
    }

    private record ResultRow(
            DnsServer server,
            DnsResult result
    ) {
    }
}