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

        @Override
        public Optional<String> resolve(String nip05) {
            int at = nip05.indexOf('@');
            if (at <= 0) {
                return Optional.empty();
            }
            String local = nip05.substring(0, at);
            String domain = nip05.substring(at + 1);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + domain
                                + "/.well-known/nostr.json?name=" + URLEncoder.encode(local, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
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

    /** JNDI DNS: MX first, then A. */
    public static final class DnsMailDomainChecker implements MailDomainChecker {
        @Override
        public boolean acceptsMail(String domain) {
            try {
                Hashtable<String, String> env = new Hashtable<>();
                env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                env.put("com.sun.jndi.dns.timeout.initial", "2000");
                env.put("com.sun.jndi.dns.timeout.retries", "1");
                Attributes attrs = new InitialDirContext(env).getAttributes("dns:/" + domain, new String[] {"MX", "A"});
                Attribute mx = attrs.get("MX");
                if (mx != null && mx.size() > 0) {
                    // RFC 7505 null MX ("0 .") means the domain accepts no mail.
                    return !(mx.size() == 1 && mx.get(0).toString().trim().endsWith(" ."));
                }
                Attribute a = attrs.get("A");
                return a != null && a.size() > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
