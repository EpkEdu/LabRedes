package com.example.dns;


import java.util.List;

public record ParsedDnsResponse(
        int rcode,
        String rcodeDescription,
        List<String> ipAddresses
) {
}