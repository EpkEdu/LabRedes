package com.example.dns;

public record DnsResult(
        double average,
        double minimum,
        double maximum,
        double packetLoss
) {
}