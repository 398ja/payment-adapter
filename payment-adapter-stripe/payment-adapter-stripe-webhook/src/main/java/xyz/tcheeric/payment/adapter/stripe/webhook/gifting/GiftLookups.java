package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Hashtable;
import java.util.Optional;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

/** The outside lookups a draft makes, behind interfaces so tests never touch the network. */
public final class GiftLookups {

    private GiftLookups() {
    }

    /** {@code name@domain} → hex pubkey, re-resolved at draft time and then pinned (§3). */
    public interface HandleResolver {
        Optional<String> resolve(String nip05);
    }

    /** Can this address's domain receive mail (MX, or A per RFC 5321 fallback)? */
    public interface MailDomainChecker {
        boolean acceptsMail(String domain);
    }

    /** NIP-05 over HTTPS: {@code https://<domain>/.well-known/nostr.json?name=<local>}. */
    public static final class WellKnownResolver implements HandleResolver {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        /**
         * Per-environment: a handle domain answered by an internal base URL instead of
         * {@code https://<domain>} (a local stack's {@code imani.local} has no TLS front). Empty in
         * every real deployment, so a handle always resolves over HTTPS from its own domain there.
         */
        private final java.util.Map<String, String> overrides;

        public WellKnownResolver() {
            this(java.util.Map.of());
        }

        public WellKnownResolver(java.util.Map<String, String> overrides) {
            this.overrides = java.util.Map.copyOf(overrides);
        }

        /**
         * {@code "imani.local=http://imani.local:8085"}; blank is none. Malformed refuses the boot.
         * The override keeps the handle's domain as the host (the NIP-05 server picks the domain by
         * Host), so a local stack maps that name to its NIP-05 server (compose {@code extra_hosts}).
         */
        public static java.util.Map<String, String> parseOverrides(String raw) {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            if (raw == null || raw.isBlank()) {
                return out;
            }
            for (String pair : raw.split(",")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length != 2 || !kv[0].trim().matches("^[a-z0-9.-]+$")
                        || !kv[1].trim().matches("^https?://[A-Za-z0-9.:_-]+$")) {
                    throw new IllegalArgumentException("purchase.gifting.nip05-overrides: expected domain=http(s)://host[:port], got '" + pair.trim() + "'");
                }
                out.put(kv[0].trim(), kv[1].trim());
            }
            return out;
        }

        @Override
        public Optional<String> resolve(String nip05) {
            int at = nip05.indexOf('@');
            if (at <= 0) {
                return Optional.empty();
            }
            String local = nip05.substring(0, at);
            String domain = nip05.substring(at + 1);
            try {
                String override = overrides.get(domain.toLowerCase());
                String base = override != null ? override : "https://" + domain;
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base
                                + "/.well-known/nostr.json?name=" + URLEncoder.encode(local, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(5))
                        .GET();
                HttpRequest req = builder.build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200 || res.body().length() > 64 * 1024) {
                    return Optional.empty();
                }
                JsonNode names = MAPPER.readTree(res.body()).path("names");
                String hex = names.path(local).asText("").toLowerCase();
                return hex.matches("^[0-9a-f]{64}$") ? Optional.of(hex) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    /**
     * JNDI DNS: MX first, then A, as two queries. One query naming both types is answered SERVFAIL
     * by Docker's embedded resolver (and some stub resolvers), which read every address as "can't
     * receive mail" (found by the #410 G14 local run). A domain with no MX falls back to its A
     * record (RFC 5321 5.1). A lookup that fails outright is "no", as before: a typo'd domain must
     * not be sold a gift that cannot arrive.
     */
    public static final class DnsMailDomainChecker implements MailDomainChecker {
        @Override
        public boolean acceptsMail(String domain) {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            Attribute mx = lookup(env, domain, "MX");
            if (mx != null && mx.size() > 0) {
                // RFC 7505 null MX ("0 .") means the domain accepts no mail.
                try {
                    return !(mx.size() == 1 && mx.get(0).toString().trim().endsWith(" ."));
                } catch (Exception e) {
                    return false;
                }
            }
            Attribute a = lookup(env, domain, "A");
            return a != null && a.size() > 0;
        }

        private static Attribute lookup(Hashtable<String, String> env, String domain, String type) {
            try {
                return new InitialDirContext(env).getAttributes("dns:/" + domain, new String[] {type}).get(type);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
