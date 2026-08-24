import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PromptHub provider-neutral payment + prompt backend.
 *
 * This Java file does NOT implement PayPal, Venmo, Stripe, Square,
 * Adyen, Braintree, or any other provider-specific payment API.
 *
 * Google Pay is the browser wallet/tokenization layer.
 * The backend receives the Google Pay gateway token and forwards it
 * unchanged to a separately configured gateway adapter.
 *
 * Required to process a real payment:
 *
 *   PAYMENT_GATEWAY_ADAPTER_URL
 *
 * Optional:
 *
 *   PAYMENT_GATEWAY_ADAPTER_KEY
 *   PAYMENT_GATEWAY_ADAPTER_AUTH_HEADER   default: Authorization
 *   GOOGLE_PAY_GATEWAY                    e.g. the Google Pay gateway identifier
 *   GOOGLE_PAY_GATEWAY_MERCHANT_ID
 *   GOOGLE_PAY_MERCHANT_ID
 *   GOOGLE_PAY_MERCHANT_NAME              default: PromptHub
 *   GOOGLE_PAY_ENV                        TEST or PRODUCTION
 *   CURRENCY_CODE                         default: USD
 *   COUNTRY_CODE                          default: US
 *   FRONTEND_ORIGIN                       default: http://localhost:8000
 *   PORT                                  default: 8081
 *
 * The configured adapter endpoint must accept:
 *
 * POST PAYMENT_GATEWAY_ADAPTER_URL
 * Content-Type: application/json
 *
 * {
 *   "gatewayToken": "<Google Pay gateway token passed unchanged>",
 *   "amount": "5.00",
 *   "currency": "USD",
 *   "promptId": "...",
 *   "creator": "...",
 *   "stamp": "..."
 * }
 *
 * and return:
 *
 * {
 *   "approved": true,
 *   "referenceId": "...",
 *   "message": "..."
 * }
 *
 * This keeps PromptHub independent of the payment provider.
 *
 * Compile:
 *
 *   javac --add-modules jdk.httpserver PaymentProcessor.java
 *
 * Run:
 *
 *   java --add-modules jdk.httpserver PaymentProcessor
 */
public final class PaymentProcessor {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int PORT = Integer.parseInt(env("PORT", "8081"));

    private static final String FRONTEND_ORIGIN =
            env("FRONTEND_ORIGIN", "http://localhost:8000");

    private static final String ADAPTER_URL =
            env("PAYMENT_GATEWAY_ADAPTER_URL", "");

    private static final String ADAPTER_KEY =
            env("PAYMENT_GATEWAY_ADAPTER_KEY", "");

    private static final String ADAPTER_AUTH_HEADER =
            env("PAYMENT_GATEWAY_ADAPTER_AUTH_HEADER", "Authorization");

    private static final String GOOGLE_GATEWAY =
            env("GOOGLE_PAY_GATEWAY", "example");

    private static final String GOOGLE_GATEWAY_MERCHANT_ID =
            env("GOOGLE_PAY_GATEWAY_MERCHANT_ID", "exampleGatewayMerchantId");

    private static final String GOOGLE_MERCHANT_ID =
            env("GOOGLE_PAY_MERCHANT_ID", "");

    private static final String GOOGLE_MERCHANT_NAME =
            env("GOOGLE_PAY_MERCHANT_NAME", "PromptHub");

    private static final String GOOGLE_ENV =
            env("GOOGLE_PAY_ENV", "TEST").toUpperCase();

    private static final String CURRENCY =
            env("CURRENCY_CODE", "USD");

    private static final String COUNTRY =
            env("COUNTRY_CODE", "US");

    private static final Path PROMPT_STORE =
            Path.of("prompts-store.json");

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", PORT),
                0
        );

        server.createContext("/health", PaymentProcessor::health);
        server.createContext("/api/config", PaymentProcessor::config);
        server.createContext("/api/prompts", PaymentProcessor::prompts);
        server.createContext("/api/payments/charge", PaymentProcessor::charge);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("PromptHub backend: http://0.0.0.0:" + PORT);
        System.out.println("Frontend origin: " + FRONTEND_ORIGIN);
        System.out.println("Google Pay environment: " + GOOGLE_ENV);
        System.out.println(
                ADAPTER_URL.isBlank()
                        ? "Gateway adapter: NOT CONFIGURED"
                        : "Gateway adapter: CONFIGURED"
        );
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!expectMethod(exchange, "GET")) return;

        json(exchange, 200,
                "{\"ok\":true,\"service\":\"prompthub-provider-neutral\"}");
    }

    private static void config(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!expectMethod(exchange, "GET")) return;

        String body = "{"
                + "\"googleEnvironment\":\"" + jsonEscape(GOOGLE_ENV) + "\","
                + "\"googleGateway\":\"" + jsonEscape(GOOGLE_GATEWAY) + "\","
                + "\"googleGatewayMerchantId\":\""
                + jsonEscape(GOOGLE_GATEWAY_MERCHANT_ID) + "\","
                + "\"googleMerchantId\":\""
                + jsonEscape(GOOGLE_MERCHANT_ID) + "\","
                + "\"googleMerchantName\":\""
                + jsonEscape(GOOGLE_MERCHANT_NAME) + "\","
                + "\"currencyCode\":\"" + jsonEscape(CURRENCY) + "\","
                + "\"countryCode\":\"" + jsonEscape(COUNTRY) + "\","
                + "\"gatewayConfigured\":" + (!ADAPTER_URL.isBlank())
                + "}";

        json(exchange, 200, body);
    }

    private static void prompts(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ensurePromptStore();
            json(exchange, 200,
                    Files.readString(PROMPT_STORE, StandardCharsets.UTF_8));
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String incoming = readBody(exchange);

            if (!looksLikeJsonObject(incoming)) {
                json(exchange, 400,
                        "{\"error\":\"Expected one JSON prompt object.\"}");
                return;
            }

            if (incoming.length() > 20_000) {
                json(exchange, 413,
                        "{\"error\":\"Prompt record too large.\"}");
                return;
            }

            ensurePromptStore();

            String current =
                    Files.readString(PROMPT_STORE, StandardCharsets.UTF_8)
                            .trim();

            if (!current.startsWith("[") || !current.endsWith("]")) {
                current = "[]";
            }

            String inner =
                    current.substring(1, current.length() - 1).trim();

            String next = inner.isEmpty()
                    ? "[" + incoming + "]"
                    : "[" + incoming + "," + inner + "]";

            Files.writeString(
                    PROMPT_STORE,
                    next,
                    StandardCharsets.UTF_8
            );

            json(exchange, 201, incoming);
            return;
        }

        methodNotAllowed(exchange, "GET, POST, OPTIONS");
    }

    private static void charge(HttpExchange exchange) throws IOException {
        if (preflight(exchange)) return;
        if (!expectMethod(exchange, "POST")) return;

        if (ADAPTER_URL.isBlank()) {
            json(exchange, 503,
                    "{\"approved\":false,"
                    + "\"message\":\"Payment gateway adapter is not configured.\"}");
            return;
        }

        String incoming = readBody(exchange);

        if (!looksLikeJsonObject(incoming)) {
            json(exchange, 400,
                    "{\"approved\":false,"
                    + "\"message\":\"Invalid payment request.\"}");
            return;
        }

        String token = jsonStringField(incoming, "gatewayToken");
        String amount = jsonStringField(incoming, "amount");
        String currency = jsonStringField(incoming, "currency");

        if (token.isBlank()) {
            json(exchange, 400,
                    "{\"approved\":false,"
                    + "\"message\":\"Missing Google Pay gateway token.\"}");
            return;
        }

        if (!validAmount(amount)) {
            json(exchange, 400,
                    "{\"approved\":false,"
                    + "\"message\":\"Invalid tip amount.\"}");
            return;
        }

        if (currency.isBlank()) {
            currency = CURRENCY;
        }

        try {
            /*
             * Google Pay gateway token is forwarded to the configured
             * gateway adapter without modifying the token itself.
             */
            String adapterPayload = "{"
                    + "\"gatewayToken\":"
                    + jsonString(token) + ","
                    + "\"amount\":"
                    + jsonString(amount) + ","
                    + "\"currency\":"
                    + jsonString(currency) + ","
                    + "\"promptId\":"
                    + jsonString(jsonStringField(incoming, "promptId")) + ","
                    + "\"creator\":"
                    + jsonString(jsonStringField(incoming, "creator")) + ","
                    + "\"stamp\":"
                    + jsonString(jsonStringField(incoming, "stamp"))
                    + "}";

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(ADAPTER_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(adapterPayload));

            if (!ADAPTER_KEY.isBlank()) {
                builder.header(
                        ADAPTER_AUTH_HEADER,
                        ADAPTER_KEY.startsWith("Bearer ")
                                ? ADAPTER_KEY
                                : "Bearer " + ADAPTER_KEY
                );
            }

            HttpResponse<String> response = HTTP.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();

            if (status < 200 || status >= 300) {
                json(exchange, 502,
                        "{\"approved\":false,"
                        + "\"message\":\"Gateway adapter rejected payment.\"}");
                return;
            }

            String responseBody = response.body();

            if (!looksLikeJsonObject(responseBody)) {
                json(exchange, 502,
                        "{\"approved\":false,"
                        + "\"message\":\"Gateway adapter returned invalid JSON.\"}");
                return;
            }

            /*
             * Return adapter result unchanged so the adapter controls
             * approved/referenceId/message semantics.
             */
            json(exchange, 200, responseBody);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();

            json(exchange, 502,
                    "{\"approved\":false,"
                    + "\"message\":\"Payment gateway request interrupted.\"}");

        } catch (Exception ex) {
            ex.printStackTrace();

            json(exchange, 502,
                    "{\"approved\":false,"
                    + "\"message\":\"Unable to reach payment gateway adapter.\"}");
        }
    }

    private static void ensurePromptStore() throws IOException {
        if (!Files.exists(PROMPT_STORE)) {
            Files.writeString(
                    PROMPT_STORE,
                    "[]",
                    StandardCharsets.UTF_8
            );
        }
    }

    private static boolean validAmount(String amount) {
        try {
            double value = Double.parseDouble(amount);
            return value >= 1.00
                    && value <= 100.00
                    && amount.matches("\\d{1,3}\\.\\d{2}");
        } catch (Exception ex) {
            return false;
        }
    }

    private static String jsonStringField(String json, String field) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""
        );

        Matcher matcher = pattern.matcher(json);

        return matcher.find()
                ? jsonUnescape(matcher.group(1))
                : "";
    }

    private static String readBody(HttpExchange exchange)
            throws IOException {

        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();
    }

    private static boolean looksLikeJsonObject(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith("{") && text.endsWith("}");
    }

    private static boolean expectMethod(
            HttpExchange exchange,
            String method
    ) throws IOException {

        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, method + ", OPTIONS");
            return false;
        }

        return true;
    }

    private static boolean preflight(HttpExchange exchange)
            throws IOException {

        addCors(exchange.getResponseHeaders());

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }

        return false;
    }

    private static void methodNotAllowed(
            HttpExchange exchange,
            String allow
    ) throws IOException {

        exchange.getResponseHeaders().set("Allow", allow);

        json(exchange, 405,
                "{\"error\":\"Method not allowed.\"}");
    }

    private static void addCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", FRONTEND_ORIGIN);
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers",
                "Content-Type, Accept");
        headers.set("Vary", "Origin");
    }

    private static void json(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {

        addCors(exchange.getResponseHeaders());

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);

        return value == null || value.isBlank()
                ? fallback
                : value;
    }

    private static String jsonString(String value) {
        return "\"" + jsonEscape(value) + "\"";
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String jsonUnescape(String value) {
        return value
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
