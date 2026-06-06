# Regax Crypto Agent

A simple Java utility and web application for live chat, simulated crypto deals, and wallet management.

## Run

Compile the Java server with Java 11 or later:

```bash
javac CryptoWebApp.java
```

Start the application:

```bash
java CryptoWebApp
```

Open your browser to:

```bash
http://localhost:8080
```

## Features

- Live chat window with market messages
- Simulated buy / sell / hold crypto deals
- Wallet balance and holdings tracking
- Sign up / sign in with a local account
- Phantom wallet sign-in support for browser-based Solana wallets
- Real Binance API credential verification and balance retrieval
- Current prices for Solana, Bitcoin, Ethereum, and Dogecoin

## Notes

- This is a simulation example only, not a real trading system.
- Orders are executed against a mock wallet and mock market prices.
- Binance API credentials are verified and can be used to fetch real account balances.
- Credentials are stored only in memory in this demo; do not use this as a production wallet or key vault.
- Do not use for actual investment or financial decisions.
