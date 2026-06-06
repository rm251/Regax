import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CryptoAgent {
    private static final String API_URL = "https://api.coingecko.com/api/v3/simple/price";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) {
        String[] coins = args.length > 0 ? args : new String[]{"solana", "bitcoin", "ethereum"};
        try {
            Map<String, Double> prices = fetchPrices(coins);
            if (prices.isEmpty()) {
                System.out.println("No price data available. Check your network or coin IDs.");
                return;
            }
            System.out.println("Crypto price snapshot (USD)");
            prices.forEach((coin, price) -> System.out.printf("- %s: $%.6f%n", coin, price));
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to fetch crypto prices: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, Double> fetchPrices(String[] coinIds) throws IOException, InterruptedException {
        String ids = String.join(",", coinIds);
        String url = API_URL + "?ids=" + ids + "&vs_currencies=usd";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected API response: " + response.statusCode());
        }

        return parsePrices(response.body(), coinIds);
    }

    private static Map<String, Double> parsePrices(String body, String[] coinIds) {
        Map<String, Double> prices = new LinkedHashMap<>();
        for (String coin : coinIds) {
            Double price = extractUsdPrice(body, coin);
            if (price != null) {
                prices.put(coin, price);
            }
        }
        return prices;
    }

    private static Double extractUsdPrice(String json, String coinId) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(coinId) + "\"\\s*:\\s*\\{[^}]*?\\"usd\\"\\s*:\\s*([0-9]+\\.?[0-9]*([eE][+-]?[0-9]+)?)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
