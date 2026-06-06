import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class CryptoWebApp {
    private static final int PORT = 8080;
    private static final String WEB_ROOT = "web";
    private static final ChatRoom CHAT = new ChatRoom();
    private static final Market MARKET = new Market();
    private static final UserStore USERS = new UserStore();
    private static final SessionStore SESSIONS = new SessionStore();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new StaticHandler());
        server.createContext("/app.js", new StaticHandler());
        server.createContext("/styles.css", new StaticHandler());
        server.createContext("/api/chat", new ChatHandler());
        server.createContext("/api/auth/signup", new AuthHandler("signup"));
        server.createContext("/api/auth/login", new AuthHandler("login"));
        server.createContext("/api/auth/phantom", new PhantomAuthHandler());
        server.createContext("/api/session", new SessionHandler());
        server.createContext("/api/exchange/connect", new ExchangeConnectHandler());
        server.createContext("/api/exchange/balance", new ExchangeBalanceHandler());
        server.createContext("/api/wallet", new WalletHandler());
        server.createContext("/api/prices", new PricesHandler());
        server.createContext("/api/trade", new TradeHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));

        System.out.println("Starting CryptoWebApp at http://localhost:" + PORT);
        server.start();
    }

    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            Path file = Path.of(WEB_ROOT, path.replaceFirst("^/", ""));
            if (Files.exists(file) && !Files.isDirectory(file)) {
                String contentType = URLConnection.guessContentTypeFromName(file.toString());
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                byte[] content = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else {
                sendJson(exchange, 404, jsonResponse(Map.of("error", "Not found")));
            }
        }
    }

    private static class AuthHandler implements HttpHandler {
        private final String mode;

        AuthHandler(String mode) {
            this.mode = mode;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, String> payload = parseJson(body);
            String username = payload.getOrDefault("username", "").trim();
            String password = payload.getOrDefault("password", "").trim();
            if (username.isEmpty() || password.isEmpty()) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Both username and password are required")));
                return;
            }

            if ("signup".equals(mode)) {
                if (USERS.exists(username)) {
                    sendJson(exchange, 409, jsonResponse(Map.of("error", "Username already exists")));
                    return;
                }
                User user = USERS.create(username, password);
                String sessionId = SESSIONS.create(username);
                setSessionCookie(exchange, sessionId);
                sendJson(exchange, 201, jsonResponse(Map.of("user", user.toMap())));
                return;
            }

            User user = USERS.authenticate(username, password);
            if (user == null) {
                sendJson(exchange, 401, jsonResponse(Map.of("error", "Invalid credentials")));
                return;
            }
            String sessionId = SESSIONS.create(username);
            setSessionCookie(exchange, sessionId);
            sendJson(exchange, 200, jsonResponse(Map.of("user", user.toMap())));
        }
    }

    private static class PhantomAuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, String> payload = parseJson(body);
            String publicKey = payload.getOrDefault("publicKey", "").trim();
            if (publicKey.isEmpty()) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Phantom public key is required")));
                return;
            }
            User user = USERS.findByPhantomKey(publicKey);
            if (user == null) {
                String username = "phantom_" + publicKey.substring(0, Math.min(publicKey.length(), 8));
                if (USERS.exists(username)) {
                    username += "_" + UUID.randomUUID().toString().substring(0, 4);
                }
                user = USERS.createAnonymous(username, publicKey);
            }
            user.setPhantomPublicKey(publicKey);
            String sessionId = SESSIONS.create(user.getUsername());
            setSessionCookie(exchange, sessionId);
            sendJson(exchange, 200, jsonResponse(Map.of("user", user.toMap())));
        }
    }

    private static class SessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            User user = getAuthenticatedUser(exchange);
            if (user == null) {
                sendJson(exchange, 200, jsonResponse(Map.of("user", Map.of())));
                return;
            }
            sendJson(exchange, 200, jsonResponse(Map.of("user", user.toMap())));
        }
    }

    private static class ExchangeConnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            User user = getAuthenticatedUser(exchange);
            if (user == null) {
                sendJson(exchange, 401, jsonResponse(Map.of("error", "Authentication required")));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, String> payload = parseJson(body);
            String exchangeName = payload.getOrDefault("exchange", "").trim();
            String apiKey = payload.getOrDefault("apiKey", "").trim();
            String apiSecret = payload.getOrDefault("apiSecret", "").trim();
            if (exchangeName.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Exchange name, API key, and secret are required")));
                return;
            }
            if (exchangeName.equalsIgnoreCase("binance")) {
                try {
                    BinanceClient.checkCredentials(apiKey, apiSecret);
                } catch (IOException e) {
                    sendJson(exchange, 400, jsonResponse(Map.of("error", "Binance connection failed: " + e.getMessage())));
                    return;
                }
            } else {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Unsupported exchange provider: " + exchangeName)));
                return;
            }
            user.addExchangeCredentials(exchangeName, apiKey, apiSecret);
            sendJson(exchange, 200, jsonResponse(Map.of("message", "Exchange connected", "exchangeAccounts", user.getExchangeAccounts())));
        }
    }

    private static class ExchangeBalanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            User user = getAuthenticatedUser(exchange);
            if (user == null) {
                sendJson(exchange, 401, jsonResponse(Map.of("error", "Authentication required")));
                return;
            }
            if (!user.getExchangeAccounts().containsKey("binance")) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Binance not connected")));
                return;
            }
            String apiKey = user.getExchangeApiKey("binance");
            String apiSecret = user.getExchangeApiSecret("binance");
            try {
                Map<String, Object> balance = BinanceClient.fetchBalances(apiKey, apiSecret);
                sendJson(exchange, 200, jsonResponse(balance));
            } catch (IOException e) {
                sendJson(exchange, 500, jsonResponse(Map.of("error", "Unable to fetch balances: " + e.getMessage())));
            }
        }
    }

    private static class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(exchange, 200, CHAT.toJson());
                return;
            }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, String> payload = parseJson(body);
            String sender = payload.getOrDefault("sender", "Guest").trim();
            String text = payload.getOrDefault("text", "").trim();
            if (text.isEmpty()) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Message text is required")));
                return;
            }
            CHAT.addMessage(sender, text);
            sendJson(exchange, 201, CHAT.toJson());
        }
    }

    private static class WalletHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            User user = getAuthenticatedUser(exchange);
            if (user == null) {
                sendJson(exchange, 401, jsonResponse(Map.of("error", "Authentication required")));
                return;
            }
            sendJson(exchange, 200, user.getWallet().toJson());
        }
    }

    private static class PricesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            sendJson(exchange, 200, MARKET.toJson());
        }
    }

    private static class TradeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJson(exchange, 405, jsonResponse(Map.of("error", "Invalid method")));
                return;
            }
            User user = getAuthenticatedUser(exchange);
            if (user == null) {
                sendJson(exchange, 401, jsonResponse(Map.of("error", "Authentication required")));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, String> payload = parseJson(body);
            String action = payload.getOrDefault("action", "hold").toLowerCase(Locale.ROOT);
            String coin = payload.getOrDefault("coin", "solana").toLowerCase(Locale.ROOT);
            double amount = parseDouble(payload.getOrDefault("amount", "0"));

            if (amount <= 0) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Amount must be greater than zero")));
                return;
            }
            if (!MARKET.hasCoin(coin)) {
                sendJson(exchange, 400, jsonResponse(Map.of("error", "Unsupported coin: " + coin)));
                return;
            }
            String resultMessage;
            switch (action) {
                case "buy":
                    resultMessage = user.getWallet().buy(coin, amount, MARKET.getPrice(coin));
                    break;
                case "sell":
                    resultMessage = user.getWallet().sell(coin, amount, MARKET.getPrice(coin));
                    break;
                case "hold":
                    resultMessage = "Hold order received. No wallet changes applied.";
                    break;
                default:
                    sendJson(exchange, 400, jsonResponse(Map.of("error", "Unknown action: " + action)));
                    return;
            }
            CHAT.addMessage("MarketBot", resultMessage);
            MARKET.updatePrices();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", resultMessage);
            response.put("wallet", user.getWallet().toMap());
            response.put("prices", MARKET.toMap());
            response.put("chat", CHAT.toMap());
            sendJson(exchange, 200, jsonResponse(response));
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static User getAuthenticatedUser(HttpExchange exchange) {
        String sessionId = getCookie(exchange, "SESSIONID");
        if (sessionId == null) {
            return null;
        }
        String username = SESSIONS.get(sessionId);
        if (username == null) {
            return null;
        }
        return USERS.get(username);
    }

    private static void setSessionCookie(HttpExchange exchange, String sessionId) {
        exchange.getResponseHeaders().add("Set-Cookie", "SESSIONID=" + sessionId + "; Path=/; HttpOnly");
    }

    private static String getCookie(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get("Cookie");
        if (values == null) {
            return null;
        }
        for (String header : values) {
            String[] cookies = header.split("; ");
            for (String cookie : cookies) {
                String[] pair = cookie.split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static String maskApiKey(String key) {
        if (key.length() <= 8) {
            return "****" + key;
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, String> parseJson(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            String[] items = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String item : items) {
                String[] pair = item.split(":", 2);
                if (pair.length == 2) {
                    String key = unquote(pair[0].trim());
                    String value = unquote(pair[1].trim());
                    values.put(key, value);
                }
            }
        }
        return values;
    }

    private static String unquote(String text) {
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.replaceAll("\\\\", "\\").replaceAll("\\\"", "\"");
    }

    private static String jsonResponse(Map<String, ?> map) {
        return map.entrySet().stream()
                .map(entry -> "\"" + escapeJson(entry.getKey()) + "\":" + toJsonValue(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String toJsonValue(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> mapValue = (Map<String, ?>) value;
            return jsonResponse(mapValue);
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> listValue = (List<?>) value;
            return listValue.stream().map(CryptoWebApp::toJsonValue).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static class ChatRoom {
        private final List<Message> messages = new ArrayList<>();

        ChatRoom() {
            messages.add(new Message("MarketBot", "Welcome! Start chatting and place a trade to see wallet updates."));
        }

        void addMessage(String sender, String text) {
            messages.add(new Message(sender, text));
            if (messages.size() > 120) {
                messages.remove(0);
            }
        }

        String toJson() {
            return jsonResponse(Map.of("messages", messages.stream().map(Message::toMap).collect(Collectors.toList())));
        }

        Map<String, Object> toMap() {
            return Map.of("messages", messages.stream().map(Message::toMap).collect(Collectors.toList()));
        }
    }

    private static class User {
        private final String username;
        private final String passwordHash;
        private String phantomPublicKey;
        private final Map<String, String> exchangeAccounts = new LinkedHashMap<>();
        private final Map<String, String> exchangeApiKeys = new LinkedHashMap<>();
        private final Map<String, String> exchangeApiSecrets = new LinkedHashMap<>();
        private final Wallet wallet = new Wallet();

        User(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
        }

        String getUsername() {
            return username;
        }

        Wallet getWallet() {
            return wallet;
        }

        void setPhantomPublicKey(String publicKey) {
            this.phantomPublicKey = publicKey;
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "username", username,
                    "phantomPublicKey", phantomPublicKey == null ? "" : phantomPublicKey,
                    "exchangeAccounts", exchangeAccounts,
                    "wallet", wallet.toMap()
            );
        }

        void addExchangeCredentials(String exchange, String apiKey, String apiSecret) {
            exchangeAccounts.put(exchange, maskApiKey(apiKey));
            exchangeApiKeys.put(exchange, apiKey);
            exchangeApiSecrets.put(exchange, apiSecret);
        }

        String getExchangeApiKey(String exchange) {
            return exchangeApiKeys.get(exchange);
        }

        String getExchangeApiSecret(String exchange) {
            return exchangeApiSecrets.get(exchange);
        }

        Map<String, String> getExchangeAccounts() {
            return exchangeAccounts;
        }
    }

    private static class UserStore {
        private final Map<String, User> users = new LinkedHashMap<>();

        synchronized boolean exists(String username) {
            return users.containsKey(username);
        }

        synchronized User create(String username, String password) {
            User user = new User(username, hashPassword(password));
            users.put(username, user);
            return user;
        }

        synchronized User createAnonymous(String username, String phantomKey) {
            User user = new User(username, hashPassword(UUID.randomUUID().toString()));
            user.setPhantomPublicKey(phantomKey);
            users.put(username, user);
            return user;
        }

        synchronized User authenticate(String username, String password) {
            User user = users.get(username);
            if (user == null) {
                return null;
            }
            if (user.passwordHash.equals(hashPassword(password))) {
                return user;
            }
            return null;
        }

        synchronized User get(String username) {
            return users.get(username);
        }

        synchronized User findByPhantomKey(String publicKey) {
            for (User user : users.values()) {
                if (publicKey.equals(user.phantomPublicKey)) {
                    return user;
                }
            }
            return null;
        }
    }

    private static class SessionStore {
        private final Map<String, String> sessions = new LinkedHashMap<>();

        synchronized String create(String username) {
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, username);
            return sessionId;
        }

        synchronized String get(String sessionId) {
            return sessions.get(sessionId);
        }
    }

    private static class BinanceClient {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private static final String BASE_URL = "https://api.binance.com";

        static void checkCredentials(String apiKey, String apiSecret) throws IOException {
            HttpResponse<String> response = sendSignedRequest(apiKey, apiSecret, "/api/v3/account");
            if (response.statusCode() != 200) {
                throw new IOException("Binance credential test failed (" + response.statusCode() + "): " + response.body());
            }
        }

        static Map<String, Object> fetchBalances(String apiKey, String apiSecret) throws IOException {
            HttpResponse<String> response = sendSignedRequest(apiKey, apiSecret, "/api/v3/account");
            if (response.statusCode() != 200) {
                throw new IOException("Binance balance fetch failed (" + response.statusCode() + "): " + response.body());
            }
            return Map.of("balances", parseBalances(response.body()));
        }

        private static List<Map<String, Object>> parseBalances(String json) {
            List<Map<String, Object>> list = new ArrayList<>();
            Matcher matcher = BALANCE_PATTERN.matcher(json);
            while (matcher.find()) {
                double free = Double.parseDouble(matcher.group(2));
                double locked = Double.parseDouble(matcher.group(3));
                if (free > 0 || locked > 0) {
                    list.add(Map.of(
                            "asset", matcher.group(1),
                            "free", free,
                            "locked", locked
                    ));
                }
            }
            return list;
        }

        private static final Pattern BALANCE_PATTERN = Pattern.compile("\\{\"asset\":\"([^\"]+)\",\"free\":\"([^\"]+)\",\"locked\":\"([^\"]+)\"\\}");

        private static HttpResponse<String> sendSignedRequest(String apiKey, String apiSecret, String path) throws IOException {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String queryString = "timestamp=" + urlEncode(timestamp);
            String signature = hmacSha256(queryString, apiSecret);
            String url = BASE_URL + path + "?" + queryString + "&signature=" + signature;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-MBX-APIKEY", apiKey)
                    .GET()
                    .build();
            try {
                return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Binance request interrupted", e);
            }
        }

        private static String hmacSha256(String data, String secret) throws IOException {
            try {
                Mac sha256Hmac = Mac.getInstance("HmacSHA256");
                SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                sha256Hmac.init(keySpec);
                byte[] rawHmac = sha256Hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : rawHmac) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new IOException("Unable to sign Binance request", e);
            }
        }

        private static String urlEncode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static class Wallet {
        private double usdBalance = 10000.0;
        private final Map<String, Double> holdings = new LinkedHashMap<>();

        Wallet() {
            holdingNames().forEach(coin -> holdings.put(coin, 0.0));
        }

        String buy(String coin, double amount, double price) {
            double cost = amount * price;
            if (cost > usdBalance) {
                return String.format("Buy failed: insufficient USD balance for %s %.4f at $%.2f.", coin, amount, price);
            }
            usdBalance -= cost;
            holdings.put(coin, holdings.getOrDefault(coin, 0.0) + amount);
            return String.format("Bought %.4f %s at $%.2f each. Spent $%.2f.", amount, coin, price, cost);
        }

        String sell(String coin, double amount, double price) {
            double available = holdings.getOrDefault(coin, 0.0);
            if (amount > available) {
                return String.format("Sell failed: you only have %.4f %s.", available, coin);
            }
            holdings.put(coin, available - amount);
            double revenue = amount * price;
            usdBalance += revenue;
            return String.format("Sold %.4f %s at $%.2f each. Gained $%.2f.", amount, coin, price, revenue);
        }

        String toJson() {
            return jsonResponse(toMap());
        }

        Map<String, Object> toMap() {
            return Map.of("usdBalance", round(usdBalance), "holdings", holdings);
        }
    }

    private static class Market {
        private final Map<String, Double> prices = new LinkedHashMap<>();

        Market() {
            prices.put("solana", 25.00);
            prices.put("bitcoin", 40000.00);
            prices.put("ethereum", 2400.00);
            prices.put("dogecoin", 0.12);
        }

        void updatePrices() {
            prices.replaceAll((coin, value) -> round(value * (0.98 + Math.random() * 0.04)));
        }

        boolean hasCoin(String coin) {
            return prices.containsKey(coin);
        }

        double getPrice(String coin) {
            return prices.getOrDefault(coin, 0.0);
        }

        String toJson() {
            return jsonResponse(toMap());
        }

        Map<String, Object> toMap() {
            return Map.of("prices", prices);
        }
    }

    private static class Message {
        private final String sender;
        private final String text;
        private final String timestamp;

        Message(String sender, String text) {
            this.sender = sender;
            this.text = text;
            this.timestamp = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now());
        }

        Map<String, Object> toMap() {
            return Map.of("sender", sender, "text", text, "timestamp", timestamp);
        }
    }

    private static List<String> holdingNames() {
        return Arrays.asList("solana", "bitcoin", "ethereum", "dogecoin");
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
