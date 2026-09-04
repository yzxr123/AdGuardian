package org.adguardian.app.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DnsPacket {
    private static final int IPV4_MIN_HEADER = 20;
    private static final int UDP_HEADER = 8;
    private static final int DNS_HEADER = 12;

    private DnsPacket() {
    }

    public static Query parseIpv4UdpQuery(byte[] packet, int length) {
        if (packet == null || length < IPV4_MIN_HEADER + UDP_HEADER + DNS_HEADER) {
            return null;
        }
        int version = (packet[0] >>> 4) & 0x0F;
        int ihl = (packet[0] & 0x0F) * 4;
        if (version != 4 || ihl < IPV4_MIN_HEADER || length < ihl + UDP_HEADER + DNS_HEADER) {
            return null;
        }
        int protocol = packet[9] & 0xFF;
        if (protocol != 17) {
            return null;
        }
        int udpOffset = ihl;
        int sourcePort = u16(packet, udpOffset);
        int destinationPort = u16(packet, udpOffset + 2);
        if (destinationPort != 53) {
            return null;
        }
        int udpLength = u16(packet, udpOffset + 4);
        if (udpLength < UDP_HEADER + DNS_HEADER) {
            return null;
        }
        int dnsOffset = udpOffset + UDP_HEADER;
        int dnsLength = Math.min(udpLength - UDP_HEADER, length - dnsOffset);
        if (dnsLength < DNS_HEADER) {
            return null;
        }
        byte[] dns = Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength);
        if ((dns[2] & 0x80) != 0) {
            return null;
        }
        String host = readQuestionName(dns);
        if (host.isEmpty()) {
            return null;
        }
        byte[] sourceIp = Arrays.copyOfRange(packet, 12, 16);
        byte[] destinationIp = Arrays.copyOfRange(packet, 16, 20);
        return new Query(sourcePort, sourceIp, destinationIp, dns, host);
    }

    public static byte[] buildNxDomain(byte[] query) {
        if (query == null || query.length < DNS_HEADER) {
            return null;
        }
        int questionEnd = questionSectionEnd(query);
        if (questionEnd <= DNS_HEADER) {
            return null;
        }
        byte[] response = Arrays.copyOf(query, questionEnd);
        int requestFlags = ((query[2] & 0xFF) << 8) | (query[3] & 0xFF);
        int flags = 0x8000 | 0x0080 | 0x0003;
        if ((requestFlags & 0x0100) != 0) {
            flags |= 0x0100;
        }
        response[2] = (byte) ((flags >>> 8) & 0xFF);
        response[3] = (byte) (flags & 0xFF);
        response[6] = 0;
        response[7] = 0;
        response[8] = 0;
        response[9] = 0;
        response[10] = 0;
        response[11] = 0;
        return response;
    }

    public static byte[] buildIpv4UdpResponse(Query query, byte[] dnsResponse) {
        if (query == null || dnsResponse == null || dnsResponse.length < DNS_HEADER) {
            return null;
        }
        int totalLength = IPV4_MIN_HEADER + UDP_HEADER + dnsResponse.length;
        if (totalLength > 65535) {
            return null;
        }
        byte[] packet = new byte[totalLength];
        packet[0] = 0x45;
        packet[1] = 0;
        putU16(packet, 2, totalLength);
        putU16(packet, 4, 0);
        putU16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 17;
        System.arraycopy(query.destinationIp, 0, packet, 12, 4);
        System.arraycopy(query.sourceIp, 0, packet, 16, 4);
        putU16(packet, 10, ipv4Checksum(packet, 0, IPV4_MIN_HEADER));

        int udpOffset = IPV4_MIN_HEADER;
        putU16(packet, udpOffset, 53);
        putU16(packet, udpOffset + 2, query.sourcePort);
        putU16(packet, udpOffset + 4, UDP_HEADER + dnsResponse.length);
        putU16(packet, udpOffset + 6, 0);
        System.arraycopy(dnsResponse, 0, packet, udpOffset + UDP_HEADER, dnsResponse.length);
        return packet;
    }

    public static boolean sameTransaction(byte[] query, byte[] response) {
        return query != null
                && response != null
                && query.length >= 2
                && response.length >= 2
                && query[0] == response[0]
                && query[1] == response[1];
    }


    private static int questionSectionEnd(byte[] dns) {
        int questionCount = u16(dns, 4);
        if (questionCount < 1 || questionCount > 4) {
            return -1;
        }
        int offset = DNS_HEADER;
        for (int question = 0; question < questionCount; question++) {
            int labels = 0;
            while (offset < dns.length && labels < 40) {
                int size = dns[offset++] & 0xFF;
                if (size == 0) {
                    break;
                }
                if ((size & 0xC0) != 0 || size > 63 || offset + size > dns.length) {
                    return -1;
                }
                offset += size;
                labels++;
            }
            if (offset + 4 > dns.length) {
                return -1;
            }
            offset += 4;
        }
        return offset;
    }

    private static String readQuestionName(byte[] dns) {
        if (dns.length < DNS_HEADER + 5 || u16(dns, 4) < 1) {
            return "";
        }
        StringBuilder host = new StringBuilder();
        int offset = DNS_HEADER;
        int labels = 0;
        while (offset < dns.length && labels < 40) {
            int size = dns[offset++] & 0xFF;
            if (size == 0) {
                break;
            }
            if ((size & 0xC0) != 0 || size > 63 || offset + size > dns.length) {
                return "";
            }
            if (host.length() > 0) {
                host.append('.');
            }
            host.append(new String(dns, offset, size, StandardCharsets.US_ASCII));
            offset += size;
            labels++;
        }
        return host.toString();
    }

    private static int ipv4Checksum(byte[] packet, int offset, int length) {
        long sum = 0;
        int end = offset + length;
        for (int index = offset; index + 1 < end; index += 2) {
            sum += ((packet[index] & 0xFF) << 8) | (packet[index + 1] & 0xFF);
            while ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >>> 16);
            }
        }
        return (int) (~sum) & 0xFFFF;
    }

    private static int u16(byte[] value, int offset) {
        return ((value[offset] & 0xFF) << 8) | (value[offset + 1] & 0xFF);
    }

    private static void putU16(byte[] value, int offset, int number) {
        value[offset] = (byte) ((number >>> 8) & 0xFF);
        value[offset + 1] = (byte) (number & 0xFF);
    }

    public static final class Query {
        private final int sourcePort;
        private final byte[] sourceIp;
        private final byte[] destinationIp;
        private final byte[] dnsPayload;
        private final String host;

        private Query(
                int sourcePort,
                byte[] sourceIp,
                byte[] destinationIp,
                byte[] dnsPayload,
                String host
        ) {
            this.sourcePort = sourcePort;
            this.sourceIp = sourceIp;
            this.destinationIp = destinationIp;
            this.dnsPayload = dnsPayload;
            this.host = host;
        }

        public byte[] dnsPayload() {
            return dnsPayload;
        }

        public String host() {
            return host;
        }
    }
}
